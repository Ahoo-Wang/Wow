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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.core.policy.ReadFailureMode
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
import java.time.Duration

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

    /** The caches of [backend], each with its policy changed by [policy]: what CoCache's settings would make it. */
    private fun cachesOf(backend: CoherenceBackend, policy: CachePolicy.Builder.() -> Unit): StateViewCaches {
        val runtime = CacheRuntime.builder().backend(backend).build().also { opened += it }
        val factory = object : StateViewCaches.CacheFactory {
            override fun <K : Any, V : Any> create(spec: CacheSpec<K, V>): Cache<K, V> =
                runtime.create(spec.toBuilder().updatePolicy { it.policy() }.build())
        }
        return StateViewCaches(factory) { queryApiOf(snapshots) }
    }

    private val opened = mutableListOf<CacheRuntime>()

    @AfterEach
    fun closeOpened() {
        opened.forEach { it.close() }
    }

    @Test
    fun `a read of the owner that does not answer in time should be unavailable`() {
        coEvery { snapshots.state("o-1") } coAnswers {
            delay(Duration.ofSeconds(5).toMillis())
            null
        }
        val cache = cachesOf(backend) { load = LoadPolicy(asyncTimeout = Duration.ofMillis(100)) }.of<OrderView>()
        (readFailure { cache.get(key) } as StateViewUnavailableException).message.assert().contains("in time")
    }

    @Test
    fun `a read of the owner beyond the reads in flight should be unavailable`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val loading = CompletableDeferred<Unit>()
        coEvery { snapshots.state("o-1") } coAnswers {
            loading.complete(Unit)
            release.await()
            null
        }
        val cache = cachesOf(backend) {
            load = LoadPolicy(maxConcurrent = 1, acquireTimeout = Duration.ofMillis(10))
        }.of<OrderView>()
        val first = async(Dispatchers.Default) { cache.get(key) }
        loading.await()
        val failure = readFailure { cache.get(StateKey("t", "o-2")) } as StateViewUnavailableException
        failure.message.assert().contains("in flight")
        release.complete(Unit)
        first.await().assert().isNull()
    }

    @Test
    fun `a read of the owner that cancels itself should be unavailable`() {
        coEvery { snapshots.state("o-1") } throws CancellationException("The owner's read gave up.")
        val failure = readFailure { caches.of<OrderView>().get(key) } as StateViewUnavailableException
        failure.message.assert().contains("cancelled")
    }

    @Test
    fun `a backend whose reads fail should be unavailable, one that holds a foreign format not`() {
        coEvery { snapshots.state(any<String>()) } returns null
        val down = failingBackend(IllegalStateException("Redis is down."))
        val failure = readFailure {
            cachesOf(down) { readFailure = ReadFailureMode.THROW }.of<OrderView>().get(key)
        } as StateViewUnavailableException
        failure.message.assert().contains("backend")
        val foreign = failingBackend(ForeignFormatException("wow.shop.order", "t/o-1", 0, null))
        val unreadable =
            readFailure { cachesOf(foreign) { readFailure = ReadFailureMode.THROW }.of<OrderView>().get(key) }
        (unreadable as CacheReadException).cause.assert().isInstanceOf(ForeignFormatException::class.java)
    }

    /** A backend whose every read of a key throws [failure], as a Redis that is down does. */
    private fun failingBackend(failure: RuntimeException): CoherenceBackend {
        val memory = InMemoryBackend()
        return object : CoherenceBackend by memory {
            override fun store(namespace: String): RemoteStore = object : RemoteStore by memory.store(namespace) {
                override fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead =
                    throw failure
            }
        }
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
        const val FORBIDDEN = 403
    }
}
