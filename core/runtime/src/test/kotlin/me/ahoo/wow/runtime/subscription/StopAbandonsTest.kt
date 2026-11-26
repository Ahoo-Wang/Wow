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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A stop abandons at once the work that has not started: the entries queued in a lane behind the one it runs, and a
 * retry waiting its backoff. Neither is handled; the checkpoint stays before them, and the next owner handles them.
 */
class StopAbandonsTest {
    private val log = InMemoryEventLog(partitions = 1)
    private val appender = Appender(log)
    private val checkpoints = InMemoryCheckpointStore()
    private val ledger = InMemoryFailureLedger()
    private val runtimes = CopyOnWriteArrayList<SubscriptionRuntime>()

    @AfterEach
    fun stop(): Unit = runBlocking { runtimes.forEach { it.stop() } }

    /** A runtime of [subscription] alone, whose stop waits far longer than the tests' bound. */
    private fun runtime(
        subscription: Subscription,
        options: SubscriptionOptions = testOptions(stopTimeout = 60.seconds),
    ) = SubscriptionRuntime("group", log, checkpoints, ledger, listOf(subscription), options).also { runtimes += it }

    @Test
    fun `the entries queued behind a running one in its lane are abandoned at once, not handled`(): Unit = runBlocking {
        // What makes it certain: the aggregate's first entry is held in its handler until the stop tells the
        // subscription it stops, which the stop does once it has emptied the lanes; the two entries behind it were
        // queued before (the window holds all three). So the lane ends with the first: handled once, and the
        // checkpoint at it.
        val told = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val handled = Handled()
        val held = Subscription.coalescing(
            NAME,
            SubscriptionFilter("shop"),
            FunctionKind.SYSTEM,
            stops = object : SubscriptionStops {
                override fun stopping(partitions: Set<Int>) {
                    told.complete(Unit)
                }

                override fun stopped(partitions: Set<Int>) = Unit
            },
        ) { entry ->
            handled.record(entry)
            if (entry.version == 1) {
                started.complete(Unit)
                told.await()
            }
        }
        val first = runtime(held)
        first.start(setOf(0))
        val at = appender.append("a")
        withTimeout(10.seconds) { started.await() }
        repeat(2) { appender.append("a") }
        eventually { first.positions().single().inFlight == 3 }
        withTimeout(10.seconds) { first.stop() }
        handled.versionsOf("a").assert().containsExactly(1)
        checkpoints.load("group", NAME, 0).assert().isEqualTo(at)
        // The next owner reads from the checkpoint: the two entries abandoned, the latest handled.
        val next = Handled()
        runtime(Subscription.coalescing(NAME, SubscriptionFilter("shop"), FunctionKind.SYSTEM) { next.record(it) })
            .start(setOf(0))
        eventually { 3 in next.versionsOf("a") }
        next.versionsOf("a").assert().doesNotContain(1)
    }

    @Test
    fun `a retry waiting its backoff is abandoned at once, neither tried again nor parked`(): Unit = runBlocking {
        // What makes it certain: the handler fails, and its retry waits a backoff of a minute, far past the test's
        // bound of 10 s for the stop; only the stop can end that wait. The attempts are counted before the stop and
        // after it, and the ledger is read once the stop returned.
        val attempts = AtomicInteger()
        val failing = Subscription(NAME, SubscriptionFilter("shop")) {
            attempts.incrementAndGet()
            error("${it.ref.id} fails")
        }
        val options = testOptions(stopTimeout = 60.seconds)
            .copyWith(retry = RetryPolicy(maxAttempts = 3, initialBackoff = 60.seconds, maxBackoff = 60.seconds))
        val first = runtime(failing, options)
        first.start(setOf(0))
        appender.append("a")
        eventually { attempts.get() == 1 }
        withTimeout(10.seconds) { first.stop() }
        attempts.get().assert().isEqualTo(1)
        ledger.list(SubscriptionId("group", NAME)).assert().isEmpty()
        (checkpoints.load("group", NAME, 0) ?: 0L).assert().isEqualTo(0L)
    }

    @Test
    fun `a handling waiting on its paused partition is given up at once, nothing parked`(): Unit = runBlocking {
        // What makes it certain: the handler's failure is systemic, so the partition pauses with a probe backoff of a
        // minute, far past the test's bound of 10 s for the stop. The aggregates' handlings wait there, for the probe's
        // backoff or its turn, and only the stop can end those waits. The ledger and the checkpoint are read once the
        // stop returned.
        val down = Subscription(NAME, SubscriptionFilter("shop")) {
            throw StorageUnavailableException("The view store is unavailable; retry later.")
        }
        val options = testOptions(stopTimeout = 60.seconds)
            .copyWith(systemic = SystemicFailureOptions(initialBackoff = 1.minutes, maxBackoff = 1.minutes))
        val first = runtime(down, options)
        first.start(setOf(0))
        listOf("x", "y", "z").forEach { appender.append(it) }
        eventually { first.paused().isNotEmpty() }
        withTimeout(10.seconds) { first.stop() }
        ledger.list(SubscriptionId("group", NAME)).assert().isEmpty()
        (checkpoints.load("group", NAME, 0) ?: 0L).assert().isEqualTo(0L)
    }

    @Test
    fun `a failure as the worker stops is not parked, on its last attempt either`(): Unit = runBlocking {
        // What makes it certain: the handler has one attempt, which fails only once the stop has told the
        // subscription it stops, after it abandoned the worker's work not started. Without the stop it would park the
        // aggregate at once; the ledger and the checkpoint are read once the stop returned.
        val told = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val failing = Subscription.coalescing(
            NAME,
            SubscriptionFilter("shop"),
            FunctionKind.SYSTEM,
            stops = object : SubscriptionStops {
                override fun stopping(partitions: Set<Int>) {
                    told.complete(Unit)
                }

                override fun stopped(partitions: Set<Int>) = Unit
            },
        ) {
            started.complete(Unit)
            told.await()
            error("${it.ref.id} fails as its worker stops")
        }
        val first = runtime(failing, testOptions(maxAttempts = 1, stopTimeout = 60.seconds))
        first.start(setOf(0))
        appender.append("a")
        withTimeout(10.seconds) { started.await() }
        withTimeout(10.seconds) { first.stop() }
        ledger.list(SubscriptionId("group", NAME)).assert().isEmpty()
        (checkpoints.load("group", NAME, 0) ?: 0L).assert().isEqualTo(0L)
    }

    @Test
    fun `an operator's skip queued behind a running entry is answered with the stop's cancellation`(): Unit =
        runBlocking {
            // What makes it certain: the aggregate's first entry is held in its handler until the stop tells the
            // subscription it stops. The skip, asked for meanwhile, waits in the aggregate's lane behind that entry,
            // where the stop finds it as it abandons the work not started.
            val told = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val held = Subscription.coalescing(
                NAME,
                SubscriptionFilter("shop"),
                FunctionKind.SYSTEM,
                stops = object : SubscriptionStops {
                    override fun stopping(partitions: Set<Int>) {
                        told.complete(Unit)
                    }

                    override fun stopped(partitions: Set<Int>) = Unit
                },
            ) {
                started.complete(Unit)
                told.await()
            }
            val first = runtime(held)
            first.start(setOf(0))
            appender.append("a")
            withTimeout(10.seconds) { started.await() }
            val skip = async(start = CoroutineStart.UNDISPATCHED) { runCatching { first.skip(NAME, cart("a")) } }
            withTimeout(10.seconds) { first.stop() }
            val answer = withTimeout(10.seconds) { skip.await() }.exceptionOrNull()
            answer.assert().isInstanceOf(CancellationException::class.java)
            answer!!.message.assert().isEqualTo("The subscription runtime stopped.")
            ledger.list(SubscriptionId("group", NAME)).assert().isEmpty()
        }

    @Test
    fun `a run that fails part way while the stop waits ends the observations of the entries after it`(): Unit =
        runBlocking {
            // What makes it certain: a run of x@2 to x@4 is held until the stop tells a second subscription it stops,
            // which the stop does once it has abandoned every worker of the partition, and then fails at x@3. The stop
            // waits for the run (its timeout is far past the test's bound), so the run's entries after x@3 go back to
            // its lane after the worker forgot its lanes; x@3, handled alone as the worker stops, is given up.
            val told = CompletableDeferred<Unit>()
            val first = CompletableDeferred<Unit>()
            val observer = RecordingObserver(NAME)
            val batching = Subscription.batching(
                NAME,
                SubscriptionFilter("shop"),
                maxRun = 16,
                runHandler = { entries ->
                    if (entries.first().entry.version == 1) {
                        first.await()
                        RunHandled(1, null)
                    } else {
                        told.await()
                        RunHandled(1, IllegalStateException("x@3 fails as its worker stops"))
                    }
                },
            ) { entry -> error("x@${entry.version} fails alone") }
            val sentinel = Subscription.coalescing(
                "sentinel",
                SubscriptionFilter("shop"),
                FunctionKind.SYSTEM,
                stops = object : SubscriptionStops {
                    override fun stopping(partitions: Set<Int>) {
                        told.complete(Unit)
                    }

                    override fun stopped(partitions: Set<Int>) = Unit
                },
            ) {}
            val runtime = SubscriptionRuntime(
                "group",
                log,
                checkpoints,
                ledger,
                listOf(batching, sentinel),
                testOptions(stopTimeout = 60.seconds),
                observer = observer,
            ).also { runtimes += it }
            runtime.start(setOf(0))
            appender.append("x")
            eventually { "x@1" in observer.opened }
            repeat(3) { appender.append("x") }
            eventually { runtime.positions().single { it.subscription == NAME }.inFlight == 4 }
            first.complete(Unit)
            eventually { "x@4" in observer.opened }
            withTimeout(10.seconds) { runtime.stop() }

            val once = (1..4).associate { "x@$it" to 1 }
            observer.opened.groupingBy { it }.eachCount().assert().isEqualTo(once)
            observer.ended.groupingBy { it }.eachCount().assert().isEqualTo(once)
            observer.failed.keys.assert().containsExactlyInAnyOrder("x@3", "x@4")
            observer.failed.getValue("x@4").assert().isInstanceOf(CancellationException::class.java)
        }

    /** Observes each handling of [subscription], recording which opened, which ended and which failed. */
    private class RecordingObserver(private val subscription: String) : Observer {
        val opened = CopyOnWriteArrayList<String>()
        val ended = CopyOnWriteArrayList<String>()
        val failed = ConcurrentHashMap<String, Throwable>()

        override fun handling(
            group: String,
            subscription: String,
            context: String,
            stream: EventStreamRecord,
            trace: Map<String, String>,
            topic: String?,
        ): Observation {
            if (subscription != this.subscription) {
                return Observation.NONE
            }
            val key = "${stream.ref.id}@${stream.version}"
            opened += key
            return object : Observation {
                override fun failed(error: Throwable) {
                    failed[key] = error
                }

                override fun end() {
                    ended += key
                }
            }
        }
    }

    private companion object {
        const val NAME = "held"
    }
}
