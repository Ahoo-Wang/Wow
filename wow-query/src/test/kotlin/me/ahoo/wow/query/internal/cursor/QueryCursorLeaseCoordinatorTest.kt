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
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

class QueryCursorLeaseCoordinatorTest {
    @Test
    fun `acquired lease should transfer ownership once and close the terminal backend state once`() {
        val closed = AtomicInteger()
        val closedPayload = AtomicReference<String>()
        val coordinator = coordinator { state ->
            closed.incrementAndGet()
            closedPayload.set(String(state.payload()))
            Mono.empty()
        }
        val first = coordinator.issue(envelope())
        val acquired = coordinator.acquire(first, envelope().binding())
        val nextPosition = position("bob")

        val next = acquired.transfer(nextPosition, NOW.plusSeconds(90))
        acquired.close().block()
        val continued = coordinator.acquire(next, envelope().binding())

        continued.envelope.position.assert().isEqualTo(nextPosition)
        closed.get().assert().isEqualTo(0)
        continued.close().block()
        continued.close().block()
        closed.get().assert().isEqualTo(1)
        closedPayload.get().assert().isEqualTo("pit-secret")
    }

    @Test
    fun `abandoned lease cleanup and observer failures should never escape the reaper`() {
        val clock = MutableClock(NOW)
        val cleanupCalls = AtomicInteger()
        val observed = AtomicInteger()
        val manager = InMemoryQueryCursorLeaseManager(SECRET, clock)
        val coordinator = QueryCursorLeaseCoordinator(
            manager,
            listOf(
                QueryCursorBackendLeaseRegistration(BACKEND) {
                    cleanupCalls.incrementAndGet()
                    Mono.error(IllegalStateException("close failed"))
                },
            ),
            QueryCursorLeaseObserver { descriptor, reason, _ ->
                descriptor.target.assert().isEqualTo(TARGET)
                descriptor.backendId.assert().isEqualTo(BACKEND)
                reason.assert().isEqualTo(QueryCursorCleanupReason.ABANDONED)
                observed.incrementAndGet()
                error("observer failed")
            },
        )
        coordinator.issue(envelope(expiresAt = NOW.plusSeconds(1)))
        clock.advance(Duration.ofSeconds(2))

        coordinator.reapExpired().block()

        cleanupCalls.get().assert().isEqualTo(1)
        observed.get().assert().isEqualTo(1)
        manager.size.assert().isEqualTo(0)
    }

    @Test
    fun `backend state must have a registered owner and cannot transfer across backends`() {
        val manager = InMemoryQueryCursorLeaseManager(SECRET, Clock.fixed(NOW, ZoneOffset.UTC))
        val withoutOwner = QueryCursorLeaseCoordinator(manager, emptyList())
        assertRejected(QueryRejectionCategory.BACKEND_UNAVAILABLE, QueryRejectionCode.BACKEND_NOT_REGISTERED) {
            withoutOwner.issue(envelope())
        }

        val coordinator = coordinator { Mono.empty() }
        val envelope = envelope()
        val acquired = coordinator.acquire(coordinator.issue(envelope), envelope.binding())
        assertRejected(QueryRejectionCategory.INVALID_CURSOR, QueryRejectionCode.INVALID_CURSOR_BINDING) {
            acquired.transfer(
                position("bob"),
                NOW.plusSeconds(90),
                QueryCursorBackendState(BackendId("mongo"), "cursor".toByteArray()),
            )
        }
        acquired.close().block()
    }

    @Test
    fun `duplicate backend owners should fail configuration before serving queries`() {
        assertThrownBy<IllegalArgumentException> {
            QueryCursorLeaseCoordinator(
                InMemoryQueryCursorLeaseManager(SECRET, Clock.fixed(NOW, ZoneOffset.UTC)),
                listOf(
                    QueryCursorBackendLeaseRegistration(BACKEND) { Mono.empty() },
                    QueryCursorBackendLeaseRegistration(BACKEND) { Mono.empty() },
                ),
            )
        }
    }

    private fun coordinator(closer: QueryCursorBackendLeaseCloser): QueryCursorLeaseCoordinator =
        QueryCursorLeaseCoordinator(
            InMemoryQueryCursorLeaseManager(SECRET, Clock.fixed(NOW, ZoneOffset.UTC)),
            listOf(QueryCursorBackendLeaseRegistration(BACKEND, closer)),
        )

    private fun envelope(
        expiresAt: Instant = NOW.plusSeconds(60),
    ) = QueryCursorEnvelope(
        TARGET,
        PlanFingerprint("1".repeat(64)),
        QueryCursorMappingDigest("a".repeat(64)),
        QueryCursorSecurityContextDigest("c".repeat(64)),
        position("alice"),
        expiresAt,
        QueryCursorBackendState(BACKEND, "pit-secret".toByteArray()),
    )

    private fun position(value: String) = QueryCursorPosition.Analytics(
        listOf(AnalyticsAlias("name")),
        listOf(NormalizedValue.Text(value)),
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
        val BACKEND = BackendId("elasticsearch")
        val TARGET = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.SNAPSHOT,
        )
    }
}
