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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.redis.RedisBackendOptions
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.read.AggregateState
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.read.SnapshotQueries
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
 * Two readers of one aggregate type, each a node with its own CoCache runtime on one Redis: what one reads from the
 * owner on a miss, the other reads from Redis.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisStateViewCachesTest {
    private val redis = GenericContainer(REDIS_IMAGE).withExposedPorts(REDIS_PORT).also { it.start() }
    private val snapshots = mockk<SnapshotQueries<RedisOrderView>>()
    private val api: TenantQueryApi = mockk {
        every { tenant(any()) } answers {
            mockk<SubscriptionQueries> { every { snapshots(RedisOrderView::class.java) } returns snapshots }
        }
    }
    private val firstNode = runtime()
    private val secondNode = runtime()
    private val first = StateViewCaches(StateViewCaches.CacheFactory.of(firstNode)) { api }
    private val second = StateViewCaches(StateViewCaches.CacheFactory.of(secondNode)) { api }
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
        redis.stop()
    }

    @Test
    fun `a state one node read from the owner should be the other node's from Redis`() = runBlocking<Unit> {
        val key = keyOf()
        coEvery { snapshots.state(key.id) } returns
            AggregateState.of(order.ref("t", key.id), 3, Scope.NONE, false, emptyMap(), RedisOrderView("book"))
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

    private companion object {
        /** CI-only and pinned, as the other backends' images are (WowContainers). */
        const val REDIS_IMAGE = "redis:7.4-alpine"
        const val REDIS_PORT = 6379
    }
}
