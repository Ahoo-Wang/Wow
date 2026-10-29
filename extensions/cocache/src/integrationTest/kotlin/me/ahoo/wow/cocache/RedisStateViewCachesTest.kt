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

import io.lettuce.core.RedisURI
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.redis.RedisBackendOptions
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.read.TenantSnapshotQueries
import me.ahoo.wow.api.read.StateView
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.read.TenantQueryApi
import me.ahoo.wow.cocache.api.StateKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import java.util.UUID

/** A view of the aggregate `shop.order`, as an api module generates one. */
@StateView("shop.order")
data class RedisOrderView(val item: String)

/**
 * The owner and two readers of one aggregate type, each a node with its own CoCache runtime on one Redis: what one
 * reads from the owner on a miss, the other reads from Redis; the owner's writes at their versions, which no replay
 * takes back and which win over a reader's fill in flight.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisStateViewCachesTest {
    private val redis = GenericContainer(REDIS_IMAGE).withExposedPorts(REDIS_PORT).also { it.start() }
    private val snapshots = mockk<TenantSnapshotQueries<RedisOrderView>>()
    private val api: TenantQueryApi = mockk {
        every { tenant(any()) } answers {
            mockk<SubscriptionQueries> { every { snapshots(RedisOrderView::class.java) } returns snapshots }
        }
    }
    private val firstNode = runtime()
    private val secondNode = runtime()
    private val ownerNode = runtime()
    private val first = StateViewCaches(StateViewCaches.CacheFactory.of(firstNode)) { api }
    private val second = StateViewCaches(StateViewCaches.CacheFactory.of(secondNode)) { api }
    private val writer = StateViewCaches(StateViewCaches.CacheFactory.of(ownerNode)) { api }
        .writerOf(RedisOrderView::class.java)
    private val order = AggregateType("shop", "order")

    private fun runtime(): CacheRuntime = CacheRuntime.builder().backend(
        RedisBackend.standalone(
            RedisURI.create(redis.host, redis.getMappedPort(REDIS_PORT)),
            RedisBackendOptions(clientName = "wow-cocache-" + UUID.randomUUID()),
        ),
    ).build()

    private fun keyOf(): StateKey = StateKey("t", UUID.randomUUID().toString())

    @AfterAll
    fun close() {
        firstNode.close()
        secondNode.close()
        ownerNode.close()
        redis.stop()
    }

    @Test
    fun `a state one node read from the owner should be the other node's from Redis`() = runBlocking<Unit> {
        val key = keyOf()
        coEvery { snapshots.state(key.id) } returns
            Snapshot.of(order.ref("t", key.id), 3, Scope.NONE, emptyMap(), false, null, 0, 0, RedisOrderView("book"))
        first.of<RedisOrderView>().get(key).assert().isEqualTo(RedisOrderView("book"))
        second.of<RedisOrderView>().get(key).assert().isEqualTo(RedisOrderView("book"))
        coVerify(exactly = 1) { snapshots.state(key.id) }
    }

    @Test
    fun `an absent aggregate should be read from the owner once, for both nodes`() = runBlocking<Unit> {
        val key = keyOf()
        coEvery { snapshots.state(key.id) } returns null
        first.of<RedisOrderView>().get(key).assert().isNull()
        second.of<RedisOrderView>().get(key).assert().isNull()
        coVerify(exactly = 1) { snapshots.state(key.id) }
    }

    @Test
    fun `a batch should read the owner once, and the other node Redis`() = runBlocking<Unit> {
        val keys = listOf(keyOf(), keyOf())
        every { snapshots.list(any()) } returns flowOf(
            *keys.map {
                Snapshot.of(order.ref("t", it.id), 1, Scope.NONE, emptyMap(), false, null, 0, 0, RedisOrderView(it.id))
            }.toTypedArray(),
        )
        val expected = keys.associateWith { RedisOrderView(it.id) }
        first.of<RedisOrderView>().getAll(keys).assert().isEqualTo(expected)
        second.of<RedisOrderView>().getAll(keys).assert().isEqualTo(expected)
        verify(exactly = 1) { snapshots.list(any()) }
    }

    @Test
    fun `the owner's writes should reach the readers in version order, a replay refused`() = runBlocking<Unit> {
        val key = keyOf()
        writer.write(key, RedisOrderView("v1"), 1).assert().isTrue()
        first.of<RedisOrderView>().get(key).assert().isEqualTo(RedisOrderView("v1"))
        writer.write(key, RedisOrderView("v2"), 2).assert().isTrue()
        writer.write(key, RedisOrderView("v1"), 1).assert().isFalse()
        writer.write(key, RedisOrderView("again"), 2).assert().isFalse()
        first.of<RedisOrderView>().get(key).assert().isEqualTo(RedisOrderView("v2"))
        second.of<RedisOrderView>().get(key).assert().isEqualTo(RedisOrderView("v2"))
        coVerify(exactly = 0) { snapshots.state(key.id) }
    }

    @Test
    fun `a deleted aggregate should not be resurrected by an older state`() = runBlocking<Unit> {
        val deleted = keyOf()
        writer.write(deleted, RedisOrderView("v1"), 1).assert().isTrue()
        writer.delete(deleted, 2).assert().isTrue()
        writer.write(deleted, RedisOrderView("v1"), 1).assert().isFalse()
        first.of<RedisOrderView>().get(deleted).assert().isNull()
        // A deletion a reader reads on a miss is a tombstone at its version too.
        val read = keyOf()
        coEvery { snapshots.state(read.id) } returns
            Snapshot.of(order.ref("t", read.id), 4, Scope.NONE, emptyMap(), true, null, 0, 0, RedisOrderView("gone"))
        first.of<RedisOrderView>().get(read).assert().isNull()
        writer.write(read, RedisOrderView("replayed"), 3).assert().isFalse()
        second.of<RedisOrderView>().get(read).assert().isNull()
    }

    @Test
    fun `the owner's write should win over a reader's fill in flight`() = runBlocking<Unit> {
        val key = keyOf()
        val loading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { snapshots.state(key.id) } coAnswers {
            loading.complete(Unit)
            release.await()
            Snapshot.of(order.ref("t", key.id), 1, Scope.NONE, emptyMap(), false, null, 0, 0, RedisOrderView("stale"))
        }
        val fill = async { first.of<RedisOrderView>().get(key) }
        loading.await()
        writer.write(key, RedisOrderView("fresh"), 4).assert().isTrue()
        release.complete(Unit)
        fill.await()
        first.of<RedisOrderView>().get(key).assert().isEqualTo(RedisOrderView("fresh"))
        second.of<RedisOrderView>().get(key).assert().isEqualTo(RedisOrderView("fresh"))
        coVerify(exactly = 1) { snapshots.state(key.id) }
    }

    private companion object {
        /** CI-only and pinned, as the other backends' images are (WowContainers). */
        const val REDIS_IMAGE = "redis:7.4-alpine"
        const val REDIS_PORT = 6379
    }
}
