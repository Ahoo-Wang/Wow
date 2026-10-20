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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.client.RetryHint
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.spi.ledger.Recoverable
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Backpressure (decision D263): a downstream that is up but overloaded is never taken for systemic. The entry is
 * retried with backoff and jitter, at least the `Retry-After`, the partition is throttled until handlings succeed again,
 * and the aggregate parks only once the backpressure budget is spent; the partition never pauses.
 */
class SubscriptionBackpressureTest {
    private val log = InMemoryEventLog(partitions = 1)
    private val appender = Appender(log)
    private val ledger = InMemoryFailureLedger()
    private val checkpoints = RecordingCheckpoints()
    private val signals = RecordingSignals()
    private val options = testOptions(maxAttempts = MAX_ATTEMPTS).copyWith(
        systemic = SystemicFailureOptions(initialBackoff = 2.milliseconds, maxBackoff = 10.milliseconds),
        backpressure = BackpressureOptions(
            maxAttempts = BACKPRESSURE_ATTEMPTS,
            initialBackoff = 1.milliseconds,
            maxBackoff = 5.milliseconds,
        ),
    )

    private fun runtime(subscription: Subscription, options: SubscriptionOptions = this.options) = SubscriptionRuntime(
        "group",
        log,
        checkpoints,
        ledger,
        listOf(subscription),
        options,
        Clock.systemUTC(),
        observer = signals,
    )

    @Test
    fun `derived commands that time out under load throttle the partition and never pause it`(): Unit = runBlocking {
        val overloaded = AtomicBoolean(true)
        val attempts = AtomicInteger()
        val handled = Handled()
        val saga = Subscription("saga", SubscriptionFilter("shop")) { entry ->
            attempts.incrementAndGet()
            if (overloaded.get()) {
                throw timedOut(entry.ref.id)
            }
            handled.record(entry)
        }
        val patient = options.copyWith(
            backpressure = BackpressureOptions(
                maxAttempts = 1_000,
                initialBackoff = 1.milliseconds,
                maxBackoff = 5.milliseconds
            ),
        )
        val runtime = runtime(saga, patient)
        AGGREGATES.forEach { appender.append(it) }
        runtime.start(setOf(0))
        // Far beyond what pauses a partition (3 aggregates within the window) and every aggregate's own budget.
        eventually { attempts.get() > AGGREGATES.size * MAX_ATTEMPTS * 2 }
        runtime.paused().assert().isEmpty()
        runtime.throttled().single().assert().satisfies({
            it.subscription.assert().isEqualTo("saga")
            it.limit.assert().isEqualTo(1)
        })
        overloaded.set(false)
        eventually { handled.size == AGGREGATES.size }
        eventually { checkpoints.load("group", "saga", 0) == appender.ends[0] }
        eventually { runtime.throttled().isEmpty() }
        runtime.stop()

        ledger.list("saga").assert().isEmpty()
        signals.events.filter { it.startsWith("paused") }.assert().isEmpty()
        signals.events.filter { "throttled" in it }.assert().containsExactly("throttled saga@0", "unthrottled saga@0")
        signals.codes.assert().containsOnly(FrameworkErrors.WAIT_TIMEOUT)
    }

    @Test
    fun `the budget of backpressure spent, the aggregate parks and the partition goes on`(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val handled = Handled()
        val saga = Subscription("saga", SubscriptionFilter("shop")) { entry ->
            if (entry.ref.id == "x") {
                attempts.incrementAndGet()
                throw WowProblemException(
                    FrameworkErrors.COMMAND_BUSY,
                    TOO_MANY_REQUESTS,
                    RetryHint(retryable = true, sameRequestId = true),
                    "The node already has its commands in flight.",
                )
            }
            handled.record(entry)
        }
        val runtime = runtime(saga)
        listOf("x", "y").forEach { appender.append(it) }
        runtime.start(setOf(0))
        eventually { ledger.isParked("saga", cart("x")) && handled.size == 1 }
        runtime.stop()

        attempts.get().assert().isEqualTo(BACKPRESSURE_ATTEMPTS)
        ledger.parked("saga", cart("x"))!!.assert().satisfies({
            it.reason.assert().contains("The node already has its commands in flight.")
            // Parked as after any spent budget: the parked retries take it from here.
            it.recoverable.assert().isEqualTo(Recoverable.UNKNOWN)
        })
        runtime.paused().assert().isEmpty()
    }

    @Test
    fun `a Retry-After is waited at least, however short the backoff`(): Unit = runBlocking {
        val times = CopyOnWriteArrayList<Long>()
        val saga = Subscription("saga", SubscriptionFilter("shop")) {
            times += System.nanoTime()
            if (times.size < 3) {
                throw WowProblemException(
                    "shop.quota.exceeded",
                    TOO_MANY_REQUESTS,
                    RetryHint(retryable = true, sameRequestId = true, retryAfter = RETRY_AFTER),
                    "Too many requests.",
                )
            }
        }
        val runtime = runtime(saga)
        appender.append("x")
        runtime.start(setOf(0))
        eventually { times.size == 3 }
        runtime.stop()

        times.zipWithNext { a, b -> (b - a).milliseconds() }.forEach { it.assert().isGreaterThanOrEqualTo(RETRY_AFTER) }
        ledger.list("saga").assert().isEmpty()
    }

    @Test
    fun `a throttled partition runs fewer handlings at once, and all of them again once it recovers`(): Unit =
        runBlocking {
            val overloaded = AtomicBoolean(true)
            val running = AtomicInteger()
            val most = AtomicInteger()
            val mostWhileThrottled = AtomicInteger()
            val measuring = AtomicBoolean(false)
            val seen = ConcurrentHashMap.newKeySet<String>()
            val saga = Subscription("saga", SubscriptionFilter("shop")) { entry ->
                val now = running.incrementAndGet()
                most.accumulateAndGet(now, ::maxOf)
                try {
                    if (overloaded.get()) {
                        if (measuring.get()) {
                            mostWhileThrottled.accumulateAndGet(now, ::maxOf)
                        }
                        seen += entry.ref.id
                        delay(2)
                        throw timedOut(entry.ref.id)
                    }
                    delay(HANDLING)
                } finally {
                    running.decrementAndGet()
                }
            }
            val slow = options.copyWith(
                backpressure = BackpressureOptions(
                    maxAttempts = 1_000,
                    initialBackoff = 1.milliseconds,
                    maxBackoff = 2.milliseconds,
                    jitter = 0.0,
                ),
            )
            val runtime = runtime(saga, slow)
            AGGREGATES.forEach { appender.append(it) }
            runtime.start(setOf(0))
            eventually { seen.size == AGGREGATES.size && runtime.throttled().singleOrNull()?.limit == 1 }
            // Once the handlings that started under a higher limit have ended: one at a time.
            delay(HANDLING)
            measuring.set(true)
            delay(THROTTLED_FOR)
            mostWhileThrottled.get().assert().isLessThanOrEqualTo(1)
            overloaded.set(false)
            eventually { runtime.throttled().isEmpty() }
            eventually { checkpoints.load("group", "saga", 0) == appender.ends[0] }
            runtime.stop()

            most.get().assert().isGreaterThan(1)
            runtime.paused().assert().isEmpty()
        }

    private fun timedOut(id: String) = IllegalStateException(
        "The saga failed.",
        WowProblemException(
            FrameworkErrors.WAIT_TIMEOUT,
            GATEWAY_TIMEOUT,
            RetryHint(retryable = true, sameRequestId = true),
            "The derived command on $id failed: ${FrameworkErrors.WAIT_TIMEOUT} The wait timed out.",
        ),
    )

    private fun Long.milliseconds(): Duration = (this / 1_000_000).milliseconds

    /** Hears the pauses, the throttles and the backpressure codes. */
    private class RecordingSignals : Observer {
        val events = CopyOnWriteArrayList<String>()
        val codes = CopyOnWriteArrayList<String>()

        override fun partitionPaused(group: String, subscription: String, partition: Int, reason: String) {
            events += "paused $subscription@$partition"
        }

        override fun partitionThrottled(group: String, subscription: String, partition: Int, throttled: Boolean) {
            events += "${if (throttled) "throttled" else "unthrottled"} $subscription@$partition"
        }

        override fun backpressured(group: String, subscription: String, partition: Int, code: String) {
            codes += code
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BACKPRESSURE_ATTEMPTS = 6
        const val TOO_MANY_REQUESTS = 429
        const val GATEWAY_TIMEOUT = 504
        val AGGREGATES = listOf("a", "b", "c", "d", "e")
        val RETRY_AFTER = 50.milliseconds
        val HANDLING = 20.milliseconds
        val THROTTLED_FOR = 100.milliseconds
    }
}
