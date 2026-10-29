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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.client.RetryHint
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.api.read.SnapshotQueries
import me.ahoo.wow.api.read.StateView
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.read.TenantQueryApi
import me.ahoo.wow.cocache.api.StateKey
import me.ahoo.wow.cocache.api.StateViewUnavailableException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.ConnectException

/** A view of `shop.order` that needs a field the owner's state does not have: one that does not decode. */
@StateView("shop.order")
data class StrictOrderView(val item: String, val required: Int)

/**
 * What a read that cannot be served throws: a [StateViewUnavailableException] when the state's source cannot be read
 * now, and anything else, a state that does not decode or an error, as it is, so a reader that falls back never hides a
 * defect.
 */
class StateViewUnavailableTest {
    private val backend = InMemoryBackend()
    private val runtime = runtimeOn(backend)
    private val snapshots = mockk<SnapshotQueries<OrderView>>()
    private val caches = StateViewCaches(StateViewCaches.CacheFactory.of(runtime)) { queryApiOf(snapshots) }
    private val key = StateKey("t", "o-1")

    @AfterEach
    fun close() {
        runtime.close()
    }

    private fun readFailure(read: suspend () -> Any?): Throwable? = runCatching { runBlocking { read() } }.exceptionOrNull()

    @Test
    fun `a miss whose owner is unreachable should be unavailable, naming the key`() {
        coEvery { snapshots.state("o-1") } throws ConnectException("Connection refused")
        val failure = readFailure { caches.of<OrderView>().get(key) } as StateViewUnavailableException
        failure.key.assert().isEqualTo(key)
        failure.message.assert().contains("unreachable")
        generateSequence(failure.cause) { it.cause }.any { it is ConnectException }.assert().isTrue()
    }

    @Test
    fun `a batch of misses whose owner answers a problem a retry may get past should be unavailable`() {
        every { snapshots.list(any()) } throws
            WowProblemException("wow.unavailable", SERVICE_UNAVAILABLE, RetryHint(true, true), "Down for a moment.")
        val keys = listOf(key, StateKey("t", "o-2"))
        val failure = readFailure { caches.of<OrderView>().getAll(keys) } as StateViewUnavailableException
        failure.keys.assert().containsExactlyInAnyOrderElementsOf(keys)
        failure.key.assert().isNull()
        failure.message.assert().contains("503")
    }

    @Test
    fun `a node with no route to the owner should be unavailable`() {
        val noRoute: TenantQueryApi = mockk {
            every { tenant(any()) } answers {
                mockk<SubscriptionQueries> {
                    every { snapshots(OrderView::class.java) } throws
                        IllegalArgumentException("No aggregate of this node has a state of OrderView.")
                }
            }
        }
        val failure = readFailure {
            StateViewCaches(StateViewCaches.CacheFactory.of(runtime)) { noRoute }.of<OrderView>().get(key)
        }
        (failure as StateViewUnavailableException).message.assert().contains("no route")
    }

    @Test
    fun `an owner's refusal or an error of the reading code should be thrown as it is`() {
        coEvery { snapshots.state("o-1") } throws
            WowProblemException("wow.forbidden", FORBIDDEN, RetryHint(false, false), "Not yours.")
        (readFailure { caches.of<OrderView>().get(key) } is StateViewUnavailableException).assert().isFalse()
        coEvery { snapshots.state("o-2") } throws IllegalStateException("A defect.")
        val defect = readFailure { caches.of<OrderView>().get(StateKey("t", "o-2")) }
        (defect is StateViewUnavailableException).assert().isFalse()
        generateSequence(defect) { it.cause }.any { it.message == "A defect." }.assert().isTrue()
    }

    @Test
    fun `a state that does not decode as its view should be thrown as it is`() = runBlocking<Unit> {
        caches.writerOf(OrderView::class.java).write(key, OrderView("book"), 1).assert().isTrue()
        runtimeOn(backend).use { other ->
            val strict = StateViewCaches(StateViewCaches.CacheFactory.of(other)) { mockk() }.of<StrictOrderView>()
            val failure = readFailure { strict.get(key) }
            failure.assert().isNotNull()
            (failure is StateViewUnavailableException).assert().isFalse()
        }
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
        const val FORBIDDEN = 403
    }
}
