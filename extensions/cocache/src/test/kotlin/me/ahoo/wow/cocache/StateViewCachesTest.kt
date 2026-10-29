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

package me.ahoo.wow.cocache

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.read.TenantSnapshotQueries
import me.ahoo.wow.cocache.api.StateKey
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.test.query.MemoryQueries
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The state view caches on CoCache's memory backend, an owner and a reader on two nodes: the owner's writes at their
 * versions, a reader's misses read from the owner at theirs, and the two meeting on one key.
 */
class StateViewCachesTest {
    private val backend = InMemoryBackend()
    private val owner = runtimeOn(backend)
    private val reader = runtimeOn(backend)
    private val snapshots = mockk<TenantSnapshotQueries<OrderView>>()
    private val api = queryApiOf(snapshots)
    private val readers = StateViewCaches(StateViewCaches.CacheFactory.of(reader)) { api }
    private val owners = StateViewCaches(StateViewCaches.CacheFactory.of(owner)) { api }
    private val writer = owners.writerOf(OrderView::class.java)
    private val key = StateKey("t", "o-1")

    @AfterEach
    fun close() {
        owner.close()
        reader.close()
    }

    @Test
    fun `a cache should be named after the aggregate its view views`() {
        readers.of<OrderView>().name.assert().isEqualTo("wow.shop.order")
        StateViewCaches.nameOf(ORDER).assert().isEqualTo("wow.shop.order")
        readers.of<OrderView>().toString().assert().isEqualTo("StateViewCache(wow.shop.order)")
    }

    @Test
    fun `a cache should read a view, and only a view of one aggregate`() {
        assertThrownBy<IllegalArgumentException> { readers.of<String>() }
        readers.of<OrderView>()
        assertThrownBy<IllegalStateException> { readers.of<OtherOrderView>() }
        readers.declare(listOf(OrderView::class.java))
        readers.of<OrderView>().assert().isSameAs(readers.of<OrderView>())
    }

    @Test
    fun `a miss should read the owner's state once, then the cache`() = runBlocking<Unit> {
        coEvery { snapshots.state("o-1") } returns stateOf("o-1", 3, "book")
        val cache = readers.of<OrderView>()
        cache.get(key).assert().isEqualTo(OrderView("book"))
        cache.get("t", "o-1").assert().isEqualTo(OrderView("book"))
        coVerify(exactly = 1) { snapshots.state("o-1") }
    }

    @Test
    fun `an absent aggregate should be cached as absent, a deleted one read as absent`() = runBlocking<Unit> {
        coEvery { snapshots.state("o-1") } returns null
        coEvery { snapshots.state("o-2") } returns stateOf("o-2", 4, "pen", deleted = true)
        val cache = readers.of<OrderView>()
        cache.get(key).assert().isNull()
        cache.get(key).assert().isNull()
        cache.get("t", "o-2").assert().isNull()
        coVerify(exactly = 1) { snapshots.state("o-1") }
    }

    /** The owner's queries as a node serves them: the real query engine over snapshots in memory. */
    private fun ownerQueries(): MemoryQueries {
        val manifest = shopManifest()
        val shop = manifest.contexts.single()
        // The order's snapshots are queried, as a context that serves its state views lists them.
        val order = shop.aggregates.single().copyWith(queries = listOf(QueryManifest(QueryRoutes.SNAPSHOT)))
        return MemoryQueries(
            manifest.copyWith(contexts = listOf(shop.copyWith(aggregates = listOf(order)))),
            mapOf(ORDER to OrderView::class.java),
        )
    }

    @Test
    fun `a batch of misses should read deleted aggregates as tombstones, as the owner's gateway answers`() =
        runBlocking<Unit> {
            val gateway = ownerQueries()
                .snapshot(ORDER, "o-1", OrderView("book"), tenantId = "t", version = 2)
                .snapshot(ORDER, "deleted", OrderView("gone"), tenantId = "t", version = 4, deleted = true)
                .snapshot(ORDER, "o-9", OrderView("pen"), tenantId = "u", version = 1)
            val cache = StateViewCaches(StateViewCaches.CacheFactory.of(reader)) { gateway }.of<OrderView>()
            val deleted = StateKey("t", "deleted")
            val keys = listOf(key, deleted, StateKey("t", "absent"), StateKey("u", "o-9"))
            cache.getAll(keys).assert().isEqualTo(
                mapOf(key to OrderView("book"), StateKey("u", "o-9") to OrderView("pen")),
            )
            // The deleted aggregate is a tombstone at its version: a replayed older write does not resurrect it.
            writer.write(deleted, OrderView("replayed"), 3).assert().isFalse()
            cache.get(deleted).assert().isNull()
            writer.write(deleted, OrderView("recreated"), 5).assert().isTrue()
            cache.get(deleted).assert().isEqualTo(OrderView("recreated"))
        }

    @Test
    fun `a batch of misses past the query budget should be read in several queries, deleted ones included`() =
        runBlocking<Unit> {
            val queries = mutableListOf<ListQuery>()
            every { snapshots.list(capture(queries)) } returns flowOf()
            val ids = (1..StateViewLoader.MAX_BATCH + 1).map { "o-$it" }
            readers.of<OrderView>().getAll(ids.map { StateKey("t", it) }).assert().isEmpty()
            queries.map { query ->
                val filters = (query.filter as AndFilter).operands
                Triple(
                    filters.filterIsInstance<IdsFilter>().single().values.size,
                    filters.filterIsInstance<DeletionFilter>().single().deletionState,
                    query.limit,
                )
            }.assert().containsExactly(
                Triple(StateViewLoader.MAX_BATCH, DeletionState.ALL, StateViewLoader.MAX_BATCH),
                Triple(1, DeletionState.ALL, 1),
            )
        }

    @Test
    fun `the owner's write should be read by every reader, with no read of the owner`() = runBlocking<Unit> {
        writer.write(key, OrderView("book"), 2).assert().isTrue()
        readers.of<OrderView>().get(key).assert().isEqualTo(OrderView("book"))
        coVerify(exactly = 0) { snapshots.state(any<String>()) }
        writer.toString().assert().isEqualTo("StateViewWriter(wow.shop.order)")
    }

    @Test
    fun `a replayed older or repeated version should be refused`() = runBlocking<Unit> {
        writer.write(key, OrderView("v2"), 2).assert().isTrue()
        writer.write(key, OrderView("v1"), 1).assert().isFalse()
        writer.write(key, OrderView("again"), 2).assert().isFalse()
        readers.of<OrderView>().get(key).assert().isEqualTo(OrderView("v2"))
        writer.write(key, OrderView("v3"), 3).assert().isTrue()
        readers.of<OrderView>().get(key).assert().isEqualTo(OrderView("v3"))
    }

    @Test
    fun `a deletion should be a tombstone that no older version resurrects`() = runBlocking<Unit> {
        writer.write(key, OrderView("v2"), 2).assert().isTrue()
        writer.delete(key, 3).assert().isTrue()
        readers.of<OrderView>().get(key).assert().isNull()
        writer.write(key, OrderView("v2"), 2).assert().isFalse()
        writer.delete(key, 3).assert().isFalse()
        readers.of<OrderView>().get(key).assert().isNull()
        coVerify(exactly = 0) { snapshots.state(any<String>()) }
    }

    @Test
    fun `a deleted aggregate read on a miss should be a tombstone at its version`() = runBlocking<Unit> {
        coEvery { snapshots.state("o-1") } returns stateOf("o-1", 4, "gone", deleted = true)
        readers.of<OrderView>().get(key).assert().isNull()
        writer.write(key, OrderView("replayed"), 3).assert().isFalse()
        readers.of<OrderView>().get(key).assert().isNull()
        writer.write(key, OrderView("recreated"), 5).assert().isTrue()
        readers.of<OrderView>().get(key).assert().isEqualTo(OrderView("recreated"))
    }

    @Test
    fun `a fill's version should be refused by no later write, and refuse an earlier one`() = runBlocking<Unit> {
        coEvery { snapshots.state("o-1") } returns stateOf("o-1", 4, "filled")
        readers.of<OrderView>().get(key).assert().isEqualTo(OrderView("filled"))
        writer.write(key, OrderView("older"), 4).assert().isFalse()
        writer.write(key, OrderView("newer"), 5).assert().isTrue()
        readers.of<OrderView>().get(key).assert().isEqualTo(OrderView("newer"))
    }

    @Test
    fun `the owner's write should win over a reader's fill in flight, and the stale fill be refused`() =
        runBlocking<Unit> {
            val loading = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { snapshots.state("o-1") } coAnswers {
                loading.complete(Unit)
                release.await()
                stateOf("o-1", 1, "stale")
            }
            val fill = async { readers.of<OrderView>().get(key) }
            // The reader holds the key's lease: the owner's versioned write takes it over.
            loading.await()
            writer.write(key, OrderView("fresh"), 5).assert().isTrue()
            release.complete(Unit)
            fill.await()
            readers.of<OrderView>().get(key).assert().isEqualTo(OrderView("fresh"))
            runtimeOn(backend).use { other ->
                StateViewCaches(StateViewCaches.CacheFactory.of(other)) { api }.of<OrderView>().get(key)
                    .assert().isEqualTo(OrderView("fresh"))
            }
            coVerify(exactly = 1) { snapshots.state("o-1") }
        }

    @Test
    fun `a view should be stored as its JSON, read past a field it does not know`() {
        val codec = StateViewCodec(OrderView::class.java)
        String(codec.encode(OrderView("book", 2))).assert().isEqualTo("""{"item":"book","quantity":2}""")
        codec.decode("""{"item":"pen","added":true}""".toByteArray()).assert().isEqualTo(OrderView("pen"))
        codec.toString().assert().contains(OrderView::class.java.name)
    }
}
