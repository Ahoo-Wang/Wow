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

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The throttle of one subscription's partition on backpressure (decision D263), step by step. */
class PartitionThrottleTest {
    private val events = CopyOnWriteArrayList<Boolean>()
    private val observer = object : Observer {
        override fun partitionThrottled(group: String, subscription: String, partition: Int, throttled: Boolean) {
            events += throttled
        }
    }

    private fun throttle(max: Int = 8, minConcurrency: Int = 1) = PartitionThrottle(
        "group",
        "saga",
        0,
        max,
        BackpressureOptions(minConcurrency = minConcurrency),
        Clock.systemUTC(),
        observer,
    )

    @Test
    fun `backpressure halves the limit down to the minimum, successes raise it back`() {
        val throttle = throttle()
        throttle.isThrottled.assert().isFalse()
        throttle.state.assert().isNull()
        throttle.backpressure(cart("x"))
        throttle.state!!.limit.assert().isEqualTo(4)
        repeat(5) { throttle.backpressure(cart("x")) }
        throttle.state!!.limit.assert().isEqualTo(1)
        throttle.state!!.subscription.assert().isEqualTo("saga")
        // Nothing waits under the limit: back to full speed at once.
        throttle.succeeded()
        throttle.isThrottled.assert().isFalse()
        throttle.succeeded()
        events.assert().containsExactly(true, false)
        throttle(minConcurrency = 3).apply { repeat(4) { backpressure(cart("x")) } }.state!!.limit.assert().isEqualTo(3)
        // A bound below the minimum is never throttled.
        throttle(max = 1, minConcurrency = 2).apply { backpressure(cart("x")) }.isThrottled.assert().isFalse()
    }

    @Test
    fun `attempts over the limit wait their turn, and the limit rises by half while some wait`(): Unit = runBlocking {
        val throttle = throttle(max = 4)
        repeat(3) { throttle.backpressure(cart("x")) }
        throttle.state!!.limit.assert().isEqualTo(1)
        throttle.acquire()
        val first = async { throttle.acquire() }
        val second = async { throttle.acquire() }
        repeat(WAITS) { yield() }
        first.isCompleted.assert().isFalse()
        // Some wait: the limit rises by half (at least one), and the longest waiter gets the room.
        throttle.succeeded()
        throttle.state!!.limit.assert().isEqualTo(2)
        first.await()
        second.isCompleted.assert().isFalse()
        throttle.release()
        second.await()
        throttle.release()
        throttle.release()
    }

    @Test
    fun `a cancelled wait leaves its turn to the next`(): Unit = runBlocking {
        val throttle = throttle(max = 2)
        repeat(2) { throttle.backpressure(cart("x")) }
        throttle.acquire()
        val cancelled = async { throttle.acquire() }
        val next = async { throttle.acquire() }
        repeat(WAITS) { yield() }
        cancelled.cancelAndJoin()
        throttle.release()
        next.await()
        throttle.release()
        // Room for one again, nothing held.
        throttle.acquire()
        throttle.release()
    }

    @Test
    fun `the options check their bounds, compare by value and jitter the backoff`() {
        assertThrows<IllegalArgumentException> { BackpressureOptions(maxAttempts = 0) }
        assertThrows<IllegalArgumentException> { BackpressureOptions(initialBackoff = Duration.ZERO) }
        assertThrows<IllegalArgumentException> {
            BackpressureOptions(
                initialBackoff = 2.seconds,
                maxBackoff = 1.seconds
            )
        }
        assertThrows<IllegalArgumentException> { BackpressureOptions(jitter = 1.5) }
        assertThrows<IllegalArgumentException> { BackpressureOptions(minConcurrency = 0) }
        val options = BackpressureOptions()
        options.assert().isEqualTo(BackpressureOptions()).isNotEqualTo(BackpressureOptions(maxAttempts = 3))
        options.hashCode().assert().isEqualTo(BackpressureOptions().hashCode())
        options.toString().assert().isEqualTo(
            "BackpressureOptions(maxAttempts=20, initialBackoff=100ms, maxBackoff=10s, jitter=0.5, minConcurrency=1)",
        )
        options.backoff(1, null, 0.0).assert().isEqualTo(100.milliseconds)
        options.backoff(1, null, 1.0).assert().isEqualTo(50.milliseconds)
        options.backoff(20, null, 0.0).assert().isEqualTo(10.seconds)
        options.backoff(1, 2.seconds, 0.5).assert().isEqualTo(2.seconds)
        SubscriptionOptions().copyWith(backpressure = BackpressureOptions(maxAttempts = 3)).backpressure.maxAttempts
            .assert().isEqualTo(3)
    }

    private companion object {
        const val WAITS = 20
    }
}
