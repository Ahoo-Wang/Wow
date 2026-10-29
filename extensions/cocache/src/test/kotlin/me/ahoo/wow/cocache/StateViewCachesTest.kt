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
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.read.SnapshotQueries
import me.ahoo.wow.cocache.api.StateKey
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The state view caches on CoCache's memory backend: a reader's misses read from the owner, and its hits. */
class StateViewCachesTest {
    private val backend = InMemoryBackend()
    private val reader = runtimeOn(backend)
    private val snapshots = mockk<SnapshotQueries<OrderView>>()
    private val api = queryApiOf(snapshots)
    private val readers = StateViewCaches(StateViewCaches.CacheFactory.of(reader)) { api }
    private val key = StateKey("t", "o-1")

    @AfterEach
    fun close() {
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

    @Test
    fun `a batch of misses should be one query per tenant, of its ids`() = runBlocking<Unit> {
        val queries = mutableListOf<ListQuery>()
        every { snapshots.list(capture(queries)) } answers {
            val ids = (queries.last().filter as IdsFilter).values
            flowOf(
                *ids.filter { it != "absent" }
                    .map { snapshotOf(it, 1, "item-$it", deleted = it == "deleted") }
                    .toTypedArray(),
            )
        }
        val keys = listOf(key, StateKey("t", "deleted"), StateKey("t", "absent"), StateKey("u", "o-9"))
        readers.of<OrderView>().getAll(keys).assert().isEqualTo(
            mapOf(key to OrderView("item-o-1"), StateKey("u", "o-9") to OrderView("item-o-9")),
        )
        verify(exactly = 2) { snapshots.list(any()) }
        queries.map { (it.filter as IdsFilter).values to it.limit }.assert().containsExactlyInAnyOrder(
            listOf("o-1", "deleted", "absent") to 3,
            listOf("o-9") to 1,
        )
    }

    @Test
    fun `a view should be stored as its JSON, read past a field it does not know`() {
        val codec = StateViewCodec(OrderView::class.java)
        String(codec.encode(OrderView("book", 2))).assert().isEqualTo("""{"item":"book","quantity":2}""")
        codec.decode("""{"item":"pen","added":true}""".toByteArray()).assert().isEqualTo(OrderView("pen"))
        codec.toString().assert().contains(OrderView::class.java.name)
    }
}
