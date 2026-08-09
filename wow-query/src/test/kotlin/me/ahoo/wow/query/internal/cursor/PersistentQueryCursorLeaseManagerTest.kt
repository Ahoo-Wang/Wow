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

@file:OptIn(
    me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.query.internal.cursor

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.cursor.QueryCursorLeaseCreateResult
import me.ahoo.wow.query.cursor.QueryCursorLeaseEntry
import me.ahoo.wow.query.cursor.QueryCursorLeaseId
import me.ahoo.wow.query.cursor.QueryCursorLeaseStore
import me.ahoo.wow.query.cursor.QueryCursorStoreRevision
import me.ahoo.wow.query.cursor.StoredQueryCursorLease
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class PersistentQueryCursorLeaseManagerTest {
    @Test
    fun `shared store should transfer one-time cursor ownership across nodes`() {
        val store = InMemoryStore()
        val firstNode = manager(store)
        val secondNode = manager(store)
        val envelope = envelope()
        val token = firstNode.issue(envelope).block()!!

        val loadedBySecond = secondNode.load(token).block()!!
        secondNode.acquire(loadedBySecond, envelope.binding()).block().assert().isEqualTo(envelope)

        StepVerifier.create(firstNode.load(token))
            .expectErrorSatisfies { error -> assertRejected(error, QueryRejectionCode.INVALID_CURSOR_TOKEN) }
            .verify()
    }

    @Test
    fun `binding mismatch should not consume the legitimate store revision`() {
        val store = InMemoryStore()
        val manager = manager(store)
        val envelope = envelope()
        val token = manager.issue(envelope).block()!!
        val loaded = manager.load(token).block()!!

        StepVerifier.create(
            manager.acquire(
                loaded,
                envelope.binding().copy(securityContextDigest = QueryCursorSecurityContextDigest("d".repeat(64))),
            ),
        ).expectErrorSatisfies { error -> assertRejected(error, QueryRejectionCode.INVALID_CURSOR_BINDING) }
            .verify()

        manager.acquire(manager.load(token).block()!!, envelope.binding()).block().assert().isEqualTo(envelope)
    }

    @Test
    fun `corrupt opaque payload and store capacity should fail with stable categories`() {
        val store = InMemoryStore()
        val manager = manager(store)
        val token = manager.issue(envelope()).block()!!
        store.corruptSinglePayload()

        StepVerifier.create(manager.load(token))
            .expectErrorSatisfies { error -> assertRejected(error, QueryRejectionCode.INVALID_CURSOR_TOKEN) }
            .verify()

        val full = InMemoryStore(capacity = 0)
        StepVerifier.create(manager(full).issue(envelope()))
            .expectErrorSatisfies { error ->
                val rejected = error as QueryRejectedException
                rejected.rejection.category.assert().isEqualTo(QueryRejectionCategory.BUDGET_EXCEEDED)
                rejected.rejection.code.assert().isEqualTo(QueryRejectionCode.CURSOR_CAPACITY_EXCEEDED)
            }.verify()
    }

    @Test
    fun `reaper should delete only the exact expired revision`() {
        val store = InMemoryStore()
        val manager = manager(store)
        manager.issue(envelope(expiresAt = NOW.plusSeconds(30))).block()
        manager.issue(envelope(expiresAt = NOW.plusSeconds(60), fingerprint = "2".repeat(64))).block()

        StepVerifier.create(manager.reapExpired(NOW.plusSeconds(45), limit = 10))
            .expectNextMatches { it.planFingerprint == PlanFingerprint("1".repeat(64)) }
            .verifyComplete()

        store.size.assert().isEqualTo(1)
    }

    private fun manager(store: QueryCursorLeaseStore): PersistentQueryCursorLeaseManager =
        PersistentQueryCursorLeaseManager(
            store,
            QueryCursorSigningKeyRing(QueryCursorSigningKey(1, SECRET)),
            Clock.fixed(NOW, ZoneOffset.UTC),
        )

    private fun envelope(
        expiresAt: Instant = NOW.plusSeconds(60),
        fingerprint: String = "1".repeat(64),
    ) = QueryCursorEnvelope(
        TARGET,
        PlanFingerprint(fingerprint),
        QueryCursorMappingDigest("a".repeat(64)),
        QueryCursorSecurityContextDigest("c".repeat(64)),
        QueryCursorPosition.Analytics(
            listOf(AnalyticsAlias("status")),
            listOf(NormalizedValue.Text("PAID")),
        ),
        expiresAt,
        QueryCursorBackendState(BackendId("elasticsearch"), "pit-id".toByteArray()),
        budgetCeiling = QueryCursorBudgetCeiling(
            maxScannedRecords = 1_000,
            maxReturnedRecords = 100,
            maxPageWindow = 10_000,
            maxCandidateBuckets = 500,
            maxReturnedBuckets = 50,
            maxCursorPages = 5,
            allowDiskUse = true,
        ),
    )

    private fun assertRejected(error: Throwable, code: QueryRejectionCode) {
        val rejected = error as QueryRejectedException
        rejected.rejection.category.assert().isEqualTo(QueryRejectionCategory.INVALID_CURSOR)
        rejected.rejection.code.assert().isEqualTo(code)
        rejected.rejection.path.toString().assert().isEqualTo("$.cursor")
    }

    private class InMemoryStore(
        private val capacity: Int = 100,
    ) : QueryCursorLeaseStore {
        private val revisions = AtomicLong()
        private val entries = ConcurrentHashMap<QueryCursorLeaseId, StoredQueryCursorLease>()

        val size: Int
            get() = entries.size

        override fun create(entry: QueryCursorLeaseEntry): Mono<QueryCursorLeaseCreateResult> = Mono.fromSupplier {
            if (entries.size >= capacity) return@fromSupplier QueryCursorLeaseCreateResult.CAPACITY_EXCEEDED
            val stored = StoredQueryCursorLease(
                entry,
                QueryCursorStoreRevision(revisions.incrementAndGet().toString()),
            )
            if (entries.putIfAbsent(entry.id, stored) == null) {
                QueryCursorLeaseCreateResult.CREATED
            } else {
                QueryCursorLeaseCreateResult.COLLISION
            }
        }

        override fun load(id: QueryCursorLeaseId): Mono<StoredQueryCursorLease> = Mono.justOrEmpty(entries[id])

        override fun compareAndDelete(expected: StoredQueryCursorLease): Mono<Boolean> =
            Mono.fromSupplier { entries.remove(expected.entry.id, expected) }

        override fun scanExpired(
            before: Instant,
            afterId: QueryCursorLeaseId?,
            limit: Int,
        ): Flux<StoredQueryCursorLease> = Flux.fromIterable(
            entries.values.filter { stored ->
                !stored.entry.expiresAt.isAfter(before) &&
                    (afterId == null || stored.entry.id.value > afterId.value)
            }.sortedBy { stored -> stored.entry.id.value }.take(limit),
        )

        fun corruptSinglePayload() {
            val current = entries.values.single()
            val payload = current.entry.payload().also { bytes -> bytes[bytes.lastIndex] = (bytes.last() + 1).toByte() }
            entries[current.entry.id] = StoredQueryCursorLease(
                QueryCursorLeaseEntry(
                    current.entry.id,
                    current.entry.expiresAt,
                    current.entry.payloadFormat,
                    payload,
                ),
                current.revision,
            )
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-09T00:00:00Z")
        val SECRET = ByteArray(32) { 7 }
        val TARGET = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.SNAPSHOT,
        )
    }
}
