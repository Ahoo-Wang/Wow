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

@file:OptIn(me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi::class)

package me.ahoo.wow.mongo.query.cursor

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.query.cursor.QueryCursorLeaseCreateResult
import me.ahoo.wow.query.cursor.QueryCursorLeaseEntry
import me.ahoo.wow.query.cursor.QueryCursorLeaseId
import me.ahoo.wow.query.cursor.QueryCursorPayloadFormat
import me.ahoo.wow.query.cursor.QueryCursorStoreRevision
import me.ahoo.wow.query.cursor.StoredQueryCursorLease
import me.ahoo.wow.tck.container.MongoTestFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class MongoQueryCursorLeaseStoreIntegrationTest {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture("query_cursor")

    private val now = Instant.parse("2026-08-09T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var store: MongoQueryCursorLeaseStore

    @BeforeEach
    fun setup() {
        store = MongoQueryCursorLeaseStore(
            mongo.database(),
            MongoQueryCursorLeaseStoreOptions(
                collectionName = "wow_query_cursor_lease_test",
                maxEntries = 8,
                retentionGrace = Duration.ofMinutes(5),
            maxPayloadBytes = 128,
            maxScanSize = 10,
            ),
            clock,
        )
        store.ensureIndexes().block()
    }

    @Test
    fun `should initialize compatible unique and grace TTL indexes idempotently`() {
        store.ensureIndexes().block()

        val indexes = mongo.database().getCollection(store.options.collectionName)
            .listIndexes().toFlux().collectList().block()!!.associateBy { index -> index.getString("name") }

        indexes.getValue("lease_id_unique").getBoolean("unique").assert().isTrue()
        (indexes.getValue("purge_at_ttl")["expireAfterSeconds"] as Number).toLong().assert().isZero()
    }

    @Test
    fun `should persist and load an immutable lease across store instances`() {
        val payload = byteArrayOf(1, 2, 3)
        val entry = entry("lease-a", now.plusSeconds(60), payload)

        store.create(entry).block().assert().isEqualTo(QueryCursorLeaseCreateResult.CREATED)
        payload[0] = 9

        val anotherStore = MongoQueryCursorLeaseStore(
            mongo.newClient().getDatabase(mongo.databaseName),
            store.options,
            clock,
        )
        val loaded = anotherStore.load(entry.id).block()!!

        loaded.entry.assert().isEqualTo(entry)
        loaded.entry.payload().toList().assert().containsExactly(1.toByte(), 2.toByte(), 3.toByte())
        loaded.revision.value.assert().isNotBlank()
    }

    @Test
    fun `should distinguish lease id collision from bounded capacity`() {
        val capacityStore = MongoQueryCursorLeaseStore(
            mongo.database(),
            store.options.copy(collectionName = "wow_query_cursor_capacity_test", maxEntries = 2),
            clock,
        )
        capacityStore.ensureIndexes().block()
        val first = entry("lease-1", now.plusSeconds(60))

        capacityStore.create(first).block().assert().isEqualTo(QueryCursorLeaseCreateResult.CREATED)
        capacityStore.create(first).block().assert().isEqualTo(QueryCursorLeaseCreateResult.COLLISION)
        capacityStore.create(entry("lease-2", now.plusSeconds(60))).block()
            .assert().isEqualTo(QueryCursorLeaseCreateResult.CREATED)
        capacityStore.create(entry("lease-3", now.plusSeconds(60))).block()
            .assert().isEqualTo(QueryCursorLeaseCreateResult.CAPACITY_EXCEEDED)
    }

    @Test
    fun `should transfer one-time ownership with revision compare and delete across nodes`() {
        val entry = entry("lease-cas", now.plusSeconds(60))
        store.create(entry).block().assert().isEqualTo(QueryCursorLeaseCreateResult.CREATED)
        val loaded = store.load(entry.id).block()!!
        val wrongRevision = StoredQueryCursorLease(
            loaded.entry,
            QueryCursorStoreRevision("wrong-revision"),
        )

        store.compareAndDelete(wrongRevision).block().assert().isFalse()

        val anotherStore = MongoQueryCursorLeaseStore(
            mongo.newClient().getDatabase(mongo.databaseName),
            store.options,
            clock,
        )
        val winners = Mono.zip(
            store.compareAndDelete(loaded),
            anotherStore.compareAndDelete(loaded),
        ).block()!!.let { result -> listOf(result.t1, result.t2) }

        winners.count { won -> won }.assert().isEqualTo(1)
        store.load(entry.id).block().assert().isNull()
    }

    @Test
    fun `should scan expired leases by stable lease id keyset`() {
        listOf(
            entry("lease-b", now.plusSeconds(20)),
            entry("lease-a", now.plusSeconds(10)),
            entry("lease-c", now.plusSeconds(40)),
        ).forEach { entry ->
            store.create(entry).block().assert().isEqualTo(QueryCursorLeaseCreateResult.CREATED)
        }

        val firstPage = store.scanExpired(now.plusSeconds(30), null, 1).collectList().block()!!
        firstPage.map { lease -> lease.entry.id.value }.assert().containsExactly("lease-a")

        val secondPage = store.scanExpired(now.plusSeconds(30), firstPage.single().entry.id, 10)
            .collectList().block()!!
        secondPage.map { lease -> lease.entry.id.value }.assert().containsExactly("lease-b")

        assertThrownBy<IllegalArgumentException> {
            store.scanExpired(now.plusSeconds(30), null, 11).collectList().block()
        }
    }

    private fun entry(
        id: String,
        expiresAt: Instant,
        payload: ByteArray = byteArrayOf(1),
    ): QueryCursorLeaseEntry = QueryCursorLeaseEntry(
        QueryCursorLeaseId(id),
        expiresAt,
        QueryCursorPayloadFormat.WOW_QUERY_CURSOR_V1,
        payload,
    )
}
