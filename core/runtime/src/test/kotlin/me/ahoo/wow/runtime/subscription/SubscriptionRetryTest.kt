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
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryFailureLedger
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

    private fun runtime(
        subscriptions: List<Subscription>,
        options: SubscriptionOptions = testOptions(
            maxAttempts = 3
        )
    ) =
        SubscriptionRuntime("group", log, RecordingCheckpoints(), ledger, subscriptions, options, Clock.systemUTC())

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
}
