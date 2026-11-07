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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.core.CacheLayers
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.Layered
import me.ahoo.cocache.core.Locks
import me.ahoo.cocache.core.SourceLoadCancelledException
import me.ahoo.cocache.core.SourceLoadRejectedException
import me.ahoo.cocache.core.SourceLoadTimeoutException
import me.ahoo.cocache.core.VersionedCacheSpec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.read.StateView
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.read.TenantQueryApi
import me.ahoo.wow.api.read.TenantSnapshotQueries
import me.ahoo.wow.cocache.api.StateKey
import me.ahoo.wow.cocache.api.StateViewUnavailableException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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
    private val snapshots = mockk<TenantSnapshotQueries<OrderView>>()
    private val caches = StateViewCaches(CoCacheFactory(runtime)) { queryApiOf(snapshots) }
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
    fun `a cache should say once that it cannot serve, and again only after it served`() {
        coEvery { snapshots.state(any<String>()) } throws ConnectException("Connection refused")
        val cache = caches.of<OrderView>()
        val events = capture {
            readFailure { cache.get(key) }
            readFailure { cache.get(StateKey("t", "o-2")) }
            // The owner's write puts the order in the cache: it serves it, so the next outage says so again.
            runBlocking { caches.writerOf(OrderView::class.java).write(key, OrderView("book"), 1) }
            runBlocking { cache.get(key) }.assert().isEqualTo(OrderView("book"))
            readFailure { cache.get(StateKey("t", "o-3")) }
        }
        events.filter { it.level == Level.WARN }.map { it.formattedMessage }.let { warned ->
            warned.size.assert().isEqualTo(2)
            warned.forEach { it.assert().startsWith("The cache wow.") }
        }
        events.filter { it.level == Level.DEBUG }.map { it.formattedMessage }.single().assert().contains("o-2")
    }

    private fun capture(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger("me.ahoo.wow.cocache") as Logger
        val level = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            block()
            return appender.list.filter { it.throwableProxy?.className == StateViewUnavailableException::class.java.name }
        } finally {
            logger.detachAppender(appender)
            logger.level = level
        }
    }

    @Test
    fun `a batch of misses whose owner answers a problem a retry may get past should be unavailable`() {
        every { snapshots.list(any()) } throws
            WowProblemException("wow.unavailable", SERVICE_UNAVAILABLE, Retry.SAME_REQUEST, null, "Down for a moment.")
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
            StateViewCaches(CoCacheFactory(runtime)) { noRoute }.of<OrderView>().get(key)
        }
        (failure as StateViewUnavailableException).message.assert().contains("no route")
    }

    @Test
    fun `an owner's refusal or an error of the reading code should be thrown as it is`() {
        coEvery { snapshots.state("o-1") } throws
            WowProblemException("wow.forbidden", FORBIDDEN, Retry.NONE, null, "Not yours.")
        (readFailure { caches.of<OrderView>().get(key) } is StateViewUnavailableException).assert().isFalse()
        coEvery { snapshots.state("o-2") } throws IllegalStateException("A defect.")
        val defect = readFailure { caches.of<OrderView>().get(StateKey("t", "o-2")) }
        (defect is StateViewUnavailableException).assert().isFalse()
        generateSequence(defect) { it.cause }.any { it.message == "A defect." }.assert().isTrue()
    }

    @Test
    fun `a state that does not decode as its view should be read from its owner, the cache kept as it is`() =
        runBlocking<Unit> {
            caches.writerOf(OrderView::class.java).write(key, OrderView("book"), 1).assert().isTrue()
            val strictSnapshots = mockk<TenantSnapshotQueries<StrictOrderView>>()
            coEvery { strictSnapshots.state("o-1") } throws IllegalStateException("The order is no StrictOrderView.")
            val strictApi: TenantQueryApi = mockk {
                every { tenant(any()) } answers {
                    mockk<SubscriptionQueries> {
                        every { snapshots(StrictOrderView::class.java) } returns strictSnapshots
                    }
                }
            }
            runtimeOn(backend).use { other ->
                val strict = StateViewCaches(CoCacheFactory(other)) { strictApi }.of<StrictOrderView>()
                val failure = readFailure { strict.get(key) }
                (failure is StateViewUnavailableException).assert().isFalse()
                generateSequence(failure) { it.cause }.any { it.message == "The order is no StrictOrderView." }
                    .assert().isTrue()
            }
            // The owner's state stays at its version: a reader of the view still reads it, without reading the owner.
            runtimeOn(backend).use { third ->
                StateViewCaches(CoCacheFactory(third)) { queryApiOf(snapshots) }.of<OrderView>().get(key)
                    .assert().isEqualTo(OrderView("book"))
            }
        }

    /** The caches of [backend], each with its policy changed by [policy]: what CoCache's settings would make it. */
    private fun cachesOf(backend: CoherenceBackend, policy: CachePolicy.Builder.() -> Unit): StateViewCaches {
        val runtime = CacheRuntime.builder().backend(backend).build().also { opened += it }
        val settings = object : CacheLayers {
            override fun <K : Any, V : Any> apply(
                spec: VersionedCacheSpec<K, V>,
                locks: Locks,
            ): Layered<VersionedCacheSpec<K, V>> =
                CacheLayers.NONE.apply(spec.toBuilder().policy(policy).build(), locks)
        }
        return StateViewCaches(CoCacheFactory(runtime, settings)) { queryApiOf(snapshots) }
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
        val cache = cachesOf(backend) { load = LoadPolicy(timeout = Duration.ofMillis(100)) }.of<OrderView>()
        (readFailure { cache.get(key) } as StateViewUnavailableException).cause.assert()
            .isInstanceOf(SourceLoadTimeoutException::class.java)
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
        failure.cause.assert().isInstanceOf(SourceLoadRejectedException::class.java)
        release.complete(Unit)
        first.await().assert().isNull()
    }

    @Test
    fun `a read of the owner that cancels itself should be unavailable`() {
        coEvery { snapshots.state("o-1") } throws CancellationException("The owner's read gave up.")
        val failure = readFailure { caches.of<OrderView>().get(key) } as StateViewUnavailableException
        failure.cause.assert().isInstanceOf(SourceLoadCancelledException::class.java)
    }

    @Test
    fun `a backend whose reads fail should be unavailable`() {
        coEvery { snapshots.state(any<String>()) } returns null
        val down = failingBackend(IllegalStateException("Redis is down."))
        val failure = readFailure {
            cachesOf(down) { readFailure = ReadFailureMode.THROW }.of<OrderView>().get(key)
        } as StateViewUnavailableException
        failure.cause.assert().isInstanceOf(CacheReadException::class.java)
    }

    /** A backend whose every read of a key throws [failure], as a Redis that is down does. */
    private fun failingBackend(failure: RuntimeException): CoherenceBackend {
        val memory = InMemoryBackend()
        return object : CoherenceBackend by memory {
            override fun store(keyspace: Keyspace): L1Store = object : ForwardingL1Store(memory.store(keyspace)) {
                override suspend fun read(
                    key: String,
                    lease: LeaseRequest?,
                    refresh: RefreshRequest?,
                    timeout: Duration?,
                ): L1Read = throw failure
            }
        }
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
        const val FORBIDDEN = 403
    }
}
