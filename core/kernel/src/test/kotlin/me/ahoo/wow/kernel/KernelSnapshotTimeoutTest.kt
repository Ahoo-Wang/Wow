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

package me.ahoo.wow.kernel

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.StoreFellBack.Capability.SNAPSHOT_LOAD
import me.ahoo.wow.spi.observation.ObservedEvent.StoreFellBack.Reason.FAILED
import me.ahoo.wow.spi.observation.ObservedEvent.StoreFellBack.Reason.TIMED_OUT
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A cache-miss load that gives no answer within [KernelOptions.snapshotLoadTimeout] is failed like one that throws:
 * cancelled, counted as timed out, and replayed from the log, so a store that stops answering costs a replay, not
 * the command's wait.
 */
class KernelSnapshotTimeoutTest {
    private val log = InMemoryEventLog()
    private val codec = EventCodec()
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private var sequence = 0
    private val fallbacks = ArrayList<ObservedEvent.StoreFellBack>()
    private val observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            if (event is ObservedEvent.StoreFellBack) fallbacks += event
        }
    }

    /** A store whose loads give no answer while [held], and record that they were cancelled. */
    private class HeldStore : SnapshotStore by InMemorySnapshotStore() {
        var held = true
        var failing = false
        var cancelled = 0

        override suspend fun load(ref: AggregateRef): SnapshotRecord? {
            if (failing) throw IllegalStateException("store down")
            if (!held) return null
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }
    }

    private suspend fun send(kernel: Kernel, body: Any) =
        kernel.execute(cartModel, Command("c-${++sequence}", "r-$sequence", ref, body))

    private fun kernel(store: SnapshotStore, timeout: Duration = 2.seconds) =
        Kernel(
            log,
            codec,
            snapshots = store,
            observer = observer,
            options = KernelOptions(snapshotLoadTimeout = timeout)
        )

    @Test
    fun `a load with no answer within its timeout should be cancelled, and the command decided on a replay`() =
        runTest {
            val replaying = Kernel(log, codec)
            send(replaying, CreateCart("ahoo"))
            send(replaying, AddItem("apple", 1))
            val store = HeldStore()
            val outcome = send(kernel(store), AddItem("apple", 2)) as CommandOutcome.Committed
            (outcome.state as CartState).items.assert().isEqualTo(mapOf("apple" to 3))
            // At the deadline, not later: the load is given up at 2 seconds of the test's clock.
            currentTime.assert().isEqualTo(2_000L)
            store.cancelled.assert().isEqualTo(1)
            fallbacks.map { it.capability to it.reason }.assert().containsExactly(SNAPSHOT_LOAD to TIMED_OUT)
        }

    @Test
    fun `a load that answers within its timeout is the cache's`() = runTest {
        val store = HeldStore().apply { held = false }
        kernel(store).tolerant(store).load(ref).assert().isNull()
        currentTime.assert().isZero()
        fallbacks.assert().isEmpty()
    }

    @Test
    fun `a timed-out load and a failed one are one outage's, warned once, until a load answers`() = runTest {
        val store = HeldStore()
        val tolerant = kernel(store).tolerant(store)
        val logged = loggedLoads {
            tolerant.load(ref).assert().isNull()
            store.failing = true
            tolerant.load(ref).assert().isNull()
            store.failing = false
            tolerant.load(ref).assert().isNull()
            // A load that answers ends the outage; the next timeout is the next outage's first.
            store.held = false
            tolerant.load(ref).assert().isNull()
            store.held = true
            tolerant.load(ref).assert().isNull()
        }
        logged.map { it.level }.assert().containsExactly(Level.WARN, Level.DEBUG, Level.DEBUG, Level.WARN)
        logged.first().formattedMessage.assert().startsWith(
            "Loading the snapshot of $ref gave no answer within 2s; replaying its log. Later failures are counted " +
                "(wow.store.fallbacks), and logged at debug level until a load succeeds.",
        )
        fallbacks.map { it.reason }.assert().containsExactly(TIMED_OUT, FAILED, TIMED_OUT, TIMED_OUT)
    }

    @Test
    fun `a caller giving up on a load is no timeout of the load`() = runTest {
        val store = HeldStore()
        val tolerant = kernel(store, timeout = 2.seconds).tolerant(store)
        assertThrows<TimeoutCancellationException> { withTimeout(1.seconds) { tolerant.load(ref) } }
        store.cancelled.assert().isEqualTo(1)
        fallbacks.assert().isEmpty()
    }

    @Test
    fun `the load timeout should be positive, two seconds by default`() {
        KernelOptions().snapshotLoadTimeout.assert().isEqualTo(2.seconds)
        KernelOptions(snapshotLoadTimeout = 5.seconds).toString().assert().contains("snapshotLoadTimeout=5s")
        KernelOptions(
            snapshotLoadTimeout = 5.seconds
        ).assert().isEqualTo(KernelOptions(snapshotLoadTimeout = 5.seconds))
        KernelOptions(snapshotLoadTimeout = 5.seconds).assert().isNotEqualTo(KernelOptions())
        assertThrownBy<IllegalArgumentException> { KernelOptions(snapshotLoadTimeout = Duration.ZERO) }
    }

    /** What the kernel logged of a snapshot load while [block] ran. */
    private suspend fun loggedLoads(block: suspend () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger("me.ahoo.wow.kernel") as Logger
        val level = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            logger.level = level
        }
        return appender.list.filter { it.formattedMessage.startsWith("Loading the snapshot of") }
    }
}
