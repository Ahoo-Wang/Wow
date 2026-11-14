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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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

    private companion object {
        const val NAME = "held"
    }
}
