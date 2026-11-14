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

package me.ahoo.wow.runtime.snapshot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A stop abandons at once the work not started and waits only for what runs: a snapshot subscription at its window
 * bound gives up its saves waiting for a write, lets the writes in flight end, and checkpoints what is done; the next
 * owner writes the rest. A handling that never ends is waited for to the stop timeout, and told.
 */
class SnapshotStopTest : SnapshotSubscriptionFixtures() {
    private val checkpoints = InMemoryCheckpointStore()

    /**
     * The snapshot subscription with writes of two snapshots, two at once: they hold at most four, so a window
     * of [WINDOW] always has saves waiting behind them.
     */
    private val small = SnapshotSubscription(
        "shop",
        listOf(counter),
        store,
        kernel,
        log,
        codec,
        views = snapshots.views,
        writeBatch = 2,
        // Two writes in flight steady and behind alike: no catch-up count raises them.
        writesInFlight = 2,
    )

    /** A runtime of the snapshot subscription alone: a window of [WINDOW], a stop timeout far above the test's bound. */
    private fun snapshotRuntime() = SubscriptionRuntime(
        "shop",
        log,
        checkpoints,
        InMemoryFailureLedger(),
        listOf(small.subscription),
        testOptions(maxInFlight = WINDOW, stopTimeout = 60.seconds),
    ).also { runtimes += it }

    private fun ids() = (1..ENTRIES).map { "s$it" }

    private suspend fun commitAll() = ids().forEach {
        executor.execute(counter, command(it, Add())) as CommandOutcome.Committed
    }

    /** The highest k such that the aggregates of the first k entries each have a snapshot: the done prefix. */
    private suspend fun writtenPrefix(): Long = ids().takeWhile { store.load(ref(it)) != null }.size.toLong()

    @Test
    fun `a snapshot subscription at its window bound stops within its writes' time and checkpoints the done prefix`():
        Unit = runBlocking {
        // What makes it certain: the store holds every write until the test lets it go, so the window fills (its
        // count is seen) with two writes in flight, at most four snapshots, and the rest of its saves waiting
        // behind them. Once the stop is seen to have given up those saves (none waits, the writes still held), the
        // writes are let go: the stop ends with them, where it would wait 60 s, its timeout, for the saves given
        // up, and the test bounds it at 10 s. So the done prefix is at most four of the window's eight.
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        val runtime = snapshotRuntime()
        runtime.start(setOf(0))
        commitAll()
        eventually { runtime.positions().single().inFlight == WINDOW }
        small.writesWaiting.assert().isGreaterThan(0)
        val stopping = async(Dispatchers.Default) { runtime.stop() }
        eventually { small.writesWaiting == 0 }
        store.gate = null
        gate.complete(Unit)
        withTimeout(10.seconds) { stopping.await() }
        val prefix = writtenPrefix()
        prefix.assert().isLessThan(WINDOW.toLong())
        checkpoints.load("shop", small.subscription.name, 0).assert().isEqualTo(prefix)
    }

    @Test
    fun `the next owner writes the snapshots a stop abandoned`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        val first = snapshotRuntime()
        first.start(setOf(0))
        commitAll()
        eventually { first.positions().single().inFlight == WINDOW }
        val stopping = async(Dispatchers.Default) { first.stop() }
        eventually { small.writesWaiting == 0 }
        store.gate = null
        gate.complete(Unit)
        withTimeout(10.seconds) { stopping.await() }
        // The stop wrote at most four of the window's eight (as the test above), so some are left to the next owner.
        writtenPrefix().assert().isLessThan(WINDOW.toLong())
        // The next owner reads from the checkpoint: the saves the stop gave up are written now, the writes' partition
        // saving again once stopped.
        val next = snapshotRuntime()
        next.start(setOf(0))
        eventually { ids().all { store.load(ref(it)) != null } }
        ids().forEach { store.load(ref(it))!!.version.assert().isEqualTo(1) }
    }

    @Test
    fun `a handling that never ends is waited for to the stop timeout, then told once`(): Unit = runBlocking {
        // What makes it certain: the handler waits on a deferred nothing completes, so the stop's wait for the
        // handlings running can only time out.
        val never = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val stuck = Subscription("stuck", SubscriptionFilter("shop")) {
            started.complete(Unit)
            never.await()
        }
        val runtime = SubscriptionRuntime(
            "shop",
            log,
            InMemoryCheckpointStore(),
            InMemoryFailureLedger(),
            listOf(stuck),
            testOptions(stopTimeout = 200.milliseconds),
        ).also { runtimes += it }
        val logger = LoggerFactory.getLogger(SubscriptionRuntime::class.java.name) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            runtime.start(setOf(0))
            executor.execute(counter, command("x", Add())) as CommandOutcome.Committed
            withTimeout(10.seconds) { started.await() }
            runtime.stop()
            val told = appender.list.filter { it.level == Level.WARN && "stuck" in it.formattedMessage }
            told.size.assert().isEqualTo(1)
            told.single().formattedMessage.assert().contains("partition 0").contains("1 handlings still running")
        } finally {
            logger.detachAppender(appender)
        }
    }

    private companion object {
        const val WINDOW = 8
        const val ENTRIES = 24
    }
}
