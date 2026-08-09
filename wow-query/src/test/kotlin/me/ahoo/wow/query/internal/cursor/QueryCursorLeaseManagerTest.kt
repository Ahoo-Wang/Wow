/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.query.internal.cursor

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.function.Consumer

class QueryCursorLeaseManagerTest {
    @Test
    fun `token should be opaque tamper evident immutable and one time`() {
        val stateSource = "pit-secret-value".toByteArray()
        val manager = manager()
        val envelope = envelope(stateSource)

        val token = manager.issue(envelope)
        stateSource.fill(0)

        token.value.contains("pit-secret-value").assert().isFalse()
        String(Base64.getUrlDecoder().decode(token.value.substringBefore('.')))
            .contains("pit-secret-value")
            .assert().isFalse()
        manager.size.assert().isEqualTo(1)

        val acquired = manager.acquire(token, envelope.binding())
        acquired.assert().isEqualTo(envelope)
        String(acquired.backendState!!.payload()).assert().isEqualTo("pit-secret-value")
        manager.size.assert().isEqualTo(0)

        assertRejected(QueryRejectionCategory.INVALID_CURSOR, QueryRejectionCode.INVALID_CURSOR_TOKEN) {
            manager.acquire(token, envelope.binding())
        }
        val tampered = QueryCursorToken(
            (if (token.value.first() == 'A') 'B' else 'A') + token.value.drop(1),
        )
        assertRejected(QueryRejectionCategory.INVALID_CURSOR, QueryRejectionCode.INVALID_CURSOR_TOKEN) {
            manager.acquire(tampered, envelope.binding())
        }
    }

    @Test
    fun `token version and signing key should be bound without leaking registry state`() {
        val versionTwo = manager(keyId = 2)
        val envelope = envelope()
        val token = versionTwo.issue(envelope)

        assertRejected(QueryRejectionCategory.INVALID_CURSOR, QueryRejectionCode.INVALID_CURSOR_TOKEN) {
            manager().acquire(token, envelope.binding())
        }
        assertRejected(QueryRejectionCategory.INVALID_CURSOR, QueryRejectionCode.INVALID_CURSOR_TOKEN) {
            manager(secret = ByteArray(32) { 2 }).acquire(token, envelope.binding())
        }
    }

    @Test
    fun `signing key rotation should retain previous verification without issuing old key ids`() {
        val leaseId = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        val oldSecret = ByteArray(32) { 1 }
        val oldCodec = QueryCursorTokenCodec(
            QueryCursorSigningKeyRing(QueryCursorSigningKey(1, oldSecret)),
        )
        val oldToken = oldCodec.encode(leaseId, NOW.plusSeconds(60))
        oldSecret.fill(0)
        val rotated = QueryCursorTokenCodec(
            QueryCursorSigningKeyRing(
                QueryCursorSigningKey(2, ByteArray(32) { 2 }),
                listOf(QueryCursorSigningKey(1, ByteArray(32) { 1 })),
            ),
        )

        rotated.decode(oldToken).id.assert().isEqualTo(leaseId)
        val currentToken = rotated.encode(leaseId, NOW.plusSeconds(60))
        assertRejected(QueryRejectionCategory.INVALID_CURSOR, QueryRejectionCode.INVALID_CURSOR_TOKEN) {
            oldCodec.decode(currentToken)
        }
        val retired = QueryCursorTokenCodec(
            QueryCursorSigningKeyRing(QueryCursorSigningKey(2, ByteArray(32) { 2 })),
        )
        assertRejected(QueryRejectionCategory.INVALID_CURSOR, QueryRejectionCode.INVALID_CURSOR_TOKEN) {
            retired.decode(oldToken)
        }
    }

    @Test
    fun `signing key ring should reject duplicate ids and unbounded previous keys`() {
        assertThrownBy<IllegalArgumentException> {
            QueryCursorSigningKeyRing(
                QueryCursorSigningKey(1, SECRET),
                listOf(QueryCursorSigningKey(1, ByteArray(32) { 2 })),
            )
        }
        assertThrownBy<IllegalArgumentException> {
            QueryCursorSigningKeyRing(
                QueryCursorSigningKey(1, SECRET),
                (2..6).map { id -> QueryCursorSigningKey(id, ByteArray(32) { id.toByte() }) },
            )
        }
    }

    @Test
    fun `expired leases should reject acquisition and be transferred for cleanup`() {
        val clock = MutableClock(NOW)
        val manager = manager(clock = clock)
        val acquired = envelope(expiresAt = NOW.plusSeconds(1))
        val acquiredToken = manager.issue(acquired)
        val abandoned = envelope(expiresAt = NOW.plusSeconds(2), fingerprint = "2".repeat(64))
        manager.issue(abandoned)

        clock.advance(Duration.ofSeconds(3))

        assertRejected(QueryRejectionCategory.INVALID_CURSOR, QueryRejectionCode.CURSOR_EXPIRED) {
            manager.acquire(acquiredToken, acquired.binding())
        }
        val expired = manager.reapExpired()
        expired.assert().hasSize(2)
        expired.contains(acquired).assert().isTrue()
        expired.contains(abandoned).assert().isTrue()
        manager.size.assert().isEqualTo(0)
    }

    @Test
    fun `registry should bound ttl entry count and backend state before publishing a token`() {
        val limits = QueryCursorLeaseLimits(
            maxEntries = 1,
            maxTtl = Duration.ofSeconds(10),
            maxBackendStateBytes = 4,
        )
        val manager = manager(limits = limits)
        manager.issue(envelope("pit".toByteArray(), NOW.plusSeconds(10)))

        assertRejected(QueryRejectionCategory.BUDGET_EXCEEDED, QueryRejectionCode.CURSOR_CAPACITY_EXCEEDED) {
            manager.issue(envelope("next".toByteArray(), NOW.plusSeconds(10), "2".repeat(64)))
        }

        val ttlManager = manager(limits = limits)
        assertRejected(QueryRejectionCategory.BUDGET_EXCEEDED, QueryRejectionCode.CURSOR_CAPACITY_EXCEEDED) {
            ttlManager.issue(envelope(expiresAt = NOW.plusSeconds(11)))
        }
        assertRejected(QueryRejectionCategory.BUDGET_EXCEEDED, QueryRejectionCode.CURSOR_CAPACITY_EXCEEDED) {
            ttlManager.issue(envelope("oversized".toByteArray(), NOW.plusSeconds(10)))
        }
        ttlManager.size.assert().isEqualTo(0)
    }

    @Test
    fun `security target plan and mapping binding mismatch must not consume the legitimate lease`() {
        val manager = manager()
        val envelope = envelope()
        val token = manager.issue(envelope)
        val expected = envelope.binding()
        listOf(
            expected.copy(securityContextDigest = QueryCursorSecurityContextDigest("b".repeat(64))),
            expected.copy(planFingerprint = PlanFingerprint("2".repeat(64))),
            expected.copy(mappingGenerationDigest = QueryCursorMappingDigest("b".repeat(64))),
            expected.copy(
                target = QueryTarget(MaterializedNamedAggregate("sales", "cart"), QueryDocumentKind.SNAPSHOT),
            ),
        ).forEach { mismatched ->
            assertRejected(QueryRejectionCategory.INVALID_CURSOR, QueryRejectionCode.INVALID_CURSOR_BINDING) {
                manager.acquire(token, mismatched)
            }
            manager.size.assert().isEqualTo(1)
        }

        manager.acquire(token, expected).assert().isEqualTo(envelope)
        manager.size.assert().isEqualTo(0)
    }

    @Test
    fun `cursor position should preserve canonical scalar value semantics`() {
        val position = QueryCursorPosition.Analytics(
            listOf(AnalyticsAlias("text"), AnalyticsAlias("decimal"), AnalyticsAlias("missing")),
            listOf(
                NormalizedValue.Text("A"),
                NormalizedValue.Decimal(BigDecimal("1.00")),
                NormalizedValue.Null,
            ),
        )
        val independent = QueryCursorPosition.Analytics(
            listOf(AnalyticsAlias("text"), AnalyticsAlias("decimal"), AnalyticsAlias("missing")),
            listOf(
                NormalizedValue.Text("A"),
                NormalizedValue.Decimal(BigDecimal.ONE),
                NormalizedValue.Null,
            ),
        )

        position.assert().isEqualTo(independent)
        position.hashCode().assert().isEqualTo(independent.hashCode())
    }

    private fun manager(
        secret: ByteArray = SECRET,
        clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC),
        limits: QueryCursorLeaseLimits = QueryCursorLeaseLimits(),
        keyId: Int = 1,
    ) = InMemoryQueryCursorLeaseManager(secret, clock, limits, keyId)

    private fun envelope(
        state: ByteArray = "pit-secret-value".toByteArray(),
        expiresAt: Instant = NOW.plusSeconds(60),
        fingerprint: String = "1".repeat(64),
    ) = QueryCursorEnvelope(
        TARGET,
        PlanFingerprint(fingerprint),
        QueryCursorMappingDigest("a".repeat(64)),
        QueryCursorSecurityContextDigest("c".repeat(64)),
        QueryCursorPosition.Analytics(
            listOf(AnalyticsAlias("name")),
            listOf(NormalizedValue.Text("alice")),
        ),
        expiresAt,
        QueryCursorBackendState(BackendId("elasticsearch"), state),
    )

    private fun assertRejected(
        category: QueryRejectionCategory,
        code: QueryRejectionCode,
        action: () -> Unit,
    ) {
        assertThrownBy<QueryRejectedException>(action).satisfies(
            Consumer { error ->
                error.rejection.category.assert().isEqualTo(category)
                error.rejection.code.assert().isEqualTo(code)
                error.rejection.path.toString().assert().isEqualTo("$.cursor")
            },
        )
    }

    private class MutableClock(
        private var current: Instant,
        private val currentZone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = currentZone

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-08T00:00:00Z")
        val SECRET = ByteArray(32) { 1 }
        val TARGET = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.SNAPSHOT,
        )
    }
}
