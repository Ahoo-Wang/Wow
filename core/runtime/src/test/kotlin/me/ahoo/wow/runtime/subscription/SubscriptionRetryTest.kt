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
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.CommandBusyException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The in-process retry budget of each subscription (roadmap P.3): the host's for its name, over its handler's own
 * (`@Retry`), over the global one; a timeout counts as a failed attempt.
 */
class SubscriptionRetryTest {
    private val log = InMemoryEventLog(partitions = 2)
    private val appender = Appender(log)
    private val ledger = InMemoryFailureLedger()
    private val checkpoints = RecordingCheckpoints()

    private fun runtime(
        subscriptions: List<Subscription>,
        options: SubscriptionOptions = testOptions(
            maxAttempts = 3
        )
    ) =
        SubscriptionRuntime("group", log, checkpoints, ledger, subscriptions, options, Clock.systemUTC())

    @Test
    fun `a slow handler times out and parks after its own budget while another keeps the global one`(): Unit =
        runBlocking {
            val slowAttempts = AtomicInteger()
            val failingAttempts = AtomicInteger()
            val slow = Subscription(
                "slow",
                SubscriptionFilter("shop"),
                retry = HandlerRetry(maxAttempts = 2, timeout = 50.milliseconds),
            ) { entry ->
                if (entry.ref.id == "x") {
                    slowAttempts.incrementAndGet()
                    delay(1.minutes)
                }
            }
            val failing = Subscription("failing", SubscriptionFilter("shop")) { entry ->
                if (entry.ref.id == "x") {
                    failingAttempts.incrementAndGet()
                    error("x is broken")
                }
            }
            val runtime = runtime(listOf(slow, failing))
            appender.append("x")
            appender.append("y")
            runtime.start(setOf(0, 1))
            eventually { ledger.isParked("slow", cart("x")) && ledger.isParked("failing", cart("x")) }
            runtime.stop()

            slowAttempts.get().assert().isEqualTo(2)
            ledger.parked("slow", cart("x"))!!.reason.assert()
                .contains(HandlerTimeoutException::class.java.name)
                .contains("within 50ms")
            failingAttempts.get().assert().isEqualTo(3)
            ledger.isParked("slow", cart("y")).assert().isFalse()
            ledger.isParked("failing", cart("y")).assert().isFalse()
        }

    @Test
    fun `a busy node is backpressure, retried past the budget without parking`(): Unit = runBlocking {
        // Review R2.1-1: a command the node refused as busy never parks the aggregate that sent it.
        val attempts = AtomicInteger()
        val busy = Subscription("busy", SubscriptionFilter("shop")) { entry ->
            if (attempts.incrementAndGet() <= BUSY_ROUNDS) {
                throw CommandBusyException(
                    entry.ref,
                    "c-${entry.version}",
                    "The node already has 1 commands in flight."
                )
            }
        }
        val runtime = runtime(listOf(busy))
        appender.append("x")
        runtime.start(setOf(0, 1))
        eventually { reachedEnd("busy") }
        runtime.stop()

        attempts.get().assert().isEqualTo(BUSY_ROUNDS + 1)
        ledger.isParked("busy", cart("x")).assert().isFalse()
    }

    @Test
    fun `a handler's own timeout or an error parks its aggregate and the checkpoint moves on`(): Unit = runBlocking {
        // Finding A1-4: neither is the lane's cancellation, so neither may end the lane silently.
        val timeouts = AtomicInteger()
        val errors = AtomicInteger()
        val handled = Handled()
        val timingOut = Subscription("timing-out", SubscriptionFilter("shop")) { entry ->
            if (entry.ref.id == "x") {
                timeouts.incrementAndGet()
                withTimeout(1.milliseconds) { delay(1.minutes) }
            }
            handled.record(entry)
        }
        val erring = Subscription("erring", SubscriptionFilter("shop")) { entry ->
            if (entry.ref.id == "x") {
                errors.incrementAndGet()
                TODO("x is not handled yet")
            }
        }
        val runtime = runtime(listOf(timingOut, erring))
        listOf("x", "y", "x", "y").forEach { appender.append(it) }
        runtime.start(setOf(0, 1))
        eventually { ledger.isParked("timing-out", cart("x")) && ledger.isParked("erring", cart("x")) }
        eventually { reachedEnd("timing-out") && reachedEnd("erring") }
        runtime.stop()

        timeouts.get().assert().isEqualTo(3)
        errors.get().assert().isEqualTo(3)
        ledger.parked("timing-out", cart("x"))!!.reason.assert().contains("TimeoutCancellationException")
        ledger.parked("erring", cart("x"))!!.reason.assert().contains(NotImplementedError::class.java.name)
        handled.versionsOf("y").assert().containsExactly(1, 2)
    }

    @Test
    fun `a fatal virtual machine error is not taken for a handler failure`(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val handled = Handled()
        val fatal = Subscription("fatal", SubscriptionFilter("shop")) { entry ->
            if (entry.ref.id == "x") {
                attempts.incrementAndGet()
                throw OutOfMemoryError("not really")
            }
            handled.record(entry)
        }
        val runtime = runtime(listOf(fatal), testOptions(stopTimeout = 100.milliseconds))
        listOf("x", "y").forEach { appender.append(it) }
        runtime.start(setOf(0, 1))
        eventually { handled.size == 1 && attempts.get() == 1 }
        runtime.stop()

        attempts.get().assert().isEqualTo(1)
        ledger.isParked("fatal", cart("x")).assert().isFalse()
    }

    private suspend fun reachedEnd(subscription: String): Boolean = setOf(0, 1).all { partition ->
        val end = appender.ends[partition] ?: 0L
        end == 0L || checkpoints.load("group", subscription, partition) == end
    }

    @Test
    fun `an attempt within its timeout succeeds`(): Unit = runBlocking {
        val handled = Handled()
        val quick = Subscription(
            "quick",
            SubscriptionFilter("shop"),
            retry = HandlerRetry(timeout = 5.seconds),
        ) { entry -> handled.record(entry) }
        val runtime = runtime(listOf(quick))
        appender.append("x")
        runtime.start(setOf(0, 1))
        eventually { handled.size == 1 }
        runtime.stop()
        ledger.isParked("quick", cart("x")).assert().isFalse()
    }

    @Test
    fun `the host's budget overrides the handler's setting by setting`() {
        val annotated = Subscription(
            "annotated",
            SubscriptionFilter("shop"),
            retry = HandlerRetry(maxAttempts = 7, timeout = 2.seconds),
        ) {}
        val plain = Subscription("plain", SubscriptionFilter("shop")) {}
        val global = RetryPolicy(maxAttempts = 3, initialBackoff = 10.milliseconds, maxBackoff = 1.seconds)
        val options = SubscriptionOptions(
            retry = global,
            retries = mapOf(
                "annotated" to HandlerRetry(timeout = 30.seconds, multiplier = 3.0),
                "plain" to HandlerRetry(maxAttempts = 1),
            ),
        )

        options.retryOf(annotated).assert().isEqualTo(
            global.copy(maxAttempts = 7, multiplier = 3.0, timeout = 30.seconds),
        )
        options.retryOf(plain).assert().isEqualTo(global.copy(maxAttempts = 1))
        SubscriptionOptions(retry = global).retryOf(annotated).assert()
            .isEqualTo(global.copy(maxAttempts = 7, timeout = 2.seconds))
        SubscriptionOptions(retry = global).retryOf(plain).assert().isEqualTo(global)
        global.timeout.assert().isEqualTo(Duration.INFINITE)
    }

    @Test
    fun `a longer initial backoff raises the maximum unless the budget gives one`() {
        val global = RetryPolicy(initialBackoff = 100.milliseconds, maxBackoff = 5.seconds)
        global.with(HandlerRetry(initialBackoff = 10.seconds)).maxBackoff.assert().isEqualTo(10.seconds)
        global.with(HandlerRetry(initialBackoff = 1.seconds)).maxBackoff.assert().isEqualTo(5.seconds)
        global.with(null).assert().isSameAs(global)
        global.with(HandlerRetry.NONE).assert().isSameAs(global)
    }

    @Test
    fun `a budget that does not add up fails the runtime's assembly`() {
        val subscription = Subscription(
            "broken",
            SubscriptionFilter("shop"),
            retry = HandlerRetry(initialBackoff = 10.seconds, maxBackoff = 1.seconds),
        ) {}
        assertThrows<IllegalArgumentException> { runtime(listOf(subscription)) }
            .message.assert().contains("broken")
        assertThrows<IllegalArgumentException> { RetryPolicy(timeout = Duration.ZERO) }
    }

    private companion object {
        /** More busy rounds than the three attempts of the budget. */
        const val BUSY_ROUNDS = 6
    }
}
