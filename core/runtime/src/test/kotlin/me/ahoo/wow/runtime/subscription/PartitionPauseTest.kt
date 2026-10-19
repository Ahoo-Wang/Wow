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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.subscription.FailureClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The pause of one subscription's partition (decision D243), driven step by step on a clock the test moves. */
class PartitionPauseTest {
    private val clock = MovableClock()
    private val failure = IllegalStateException("downstream 503")

    private fun pause(options: SystemicFailureOptions = SystemicFailureOptions(initialBackoff = 1.milliseconds)) =
        PartitionPause("group", "projection", 0, options, clock)

    @Test
    fun `a running partition admits every attempt and charges each aggregate its own failures`(): Unit = runBlocking {
        val pause = pause()
        pause.admit().assert().isFalse()
        pause.failed(cart("x"), FailureClass.RECOVERABLE, failure, probing = false).assert().isFalse()
        pause.failed(cart("x"), FailureClass.UNRECOVERABLE, failure, probing = false).assert().isFalse()
        pause.isPaused.assert().isFalse()
        pause.state.assert().isNull()
        pause.awaitRunning()
    }

    @Test
    fun `a systemic failure pauses at once and the first success resumes`() {
        val pause = pause()
        pause.failed(cart("x"), FailureClass.SYSTEMIC, failure, probing = false).assert().isTrue()
        pause.state!!.assert().satisfies({
            it.since.assert().isEqualTo(clock.millis())
            it.reason.assert().startsWith("a systemic failure: java.lang.IllegalStateException: downstream 503")
        })
        // Any failure while paused is the partition's, an unrecoverable one too.
        pause.failed(cart("y"), FailureClass.UNRECOVERABLE, failure, probing = true).assert().isTrue()
        pause.succeeded(cart("y"))
        pause.isPaused.assert().isFalse()
        pause.succeeded(cart("y"))
    }

    @Test
    fun `different aggregates failing within the window pause, the same one or older failures do not`() {
        val pause = pause()
        pause.failed(cart("x"), FailureClass.RECOVERABLE, failure, probing = false).assert().isFalse()
        pause.failed(cart("x"), FailureClass.RECOVERABLE, failure, probing = false).assert().isFalse()
        pause.failed(cart("y"), FailureClass.RECOVERABLE, failure, probing = false).assert().isFalse()
        clock.advance(11.seconds.inWholeMilliseconds)
        // x and y failed more than the window ago.
        pause.failed(cart("z"), FailureClass.RECOVERABLE, failure, probing = false).assert().isFalse()
        pause.failed(cart("w"), FailureClass.RECOVERABLE, failure, probing = false).assert().isFalse()
        // A success in between forgets them too.
        pause.succeeded(cart("v"))
        pause.failed(cart("x"), FailureClass.RECOVERABLE, failure, probing = false).assert().isFalse()
        pause.failed(cart("y"), FailureClass.RECOVERABLE, failure, probing = false).assert().isFalse()
        pause.failed(cart("z"), FailureClass.RECOVERABLE, failure, probing = false).assert().isTrue()
        pause.state!!.reason.assert().startsWith("3 aggregates failed within 10s, the last (")
    }

    @Test
    fun `no failing aggregates pause when the count is off`() {
        val pause = pause(SystemicFailureOptions(failingAggregates = 0))
        listOf("x", "y", "z", "w").forEach {
            pause.failed(cart(it), FailureClass.RECOVERABLE, failure, probing = false).assert().isFalse()
        }
        pause.failed(cart("x"), FailureClass.SYSTEMIC, failure, probing = false).assert().isTrue()
    }

    @Test
    fun `a paused partition lets one probe at a time, and a resume releases the waiting ones`(): Unit = runBlocking {
        val pause = pause(SystemicFailureOptions(initialBackoff = 1.milliseconds, maxBackoff = 2.milliseconds))
        pause.failed(cart("x"), FailureClass.SYSTEMIC, failure, probing = false)
        val probing = pause.admit()
        probing.assert().isTrue()
        val waiting = async { pause.admit() }
        val writer = async { pause.awaitRunning() }
        repeat(WAITS) { yield() }
        waiting.isCompleted.assert().isFalse()
        writer.isCompleted.assert().isFalse()
        pause.failed(cart("x"), FailureClass.SYSTEMIC, failure, probing).assert().isTrue()
        pause.succeeded(cart("x"))
        pause.release(probing)
        // The waiting one finds the partition running: it attempts, not probes.
        waiting.await().assert().isFalse()
        writer.await()
        pause.release(false)
    }

    @Test
    fun `the options check their bounds and compare by value`() {
        assertThrows<IllegalArgumentException> { SystemicFailureOptions(failingAggregates = 1) }
        assertThrows<IllegalArgumentException> { SystemicFailureOptions(window = 0.seconds) }
        assertThrows<IllegalArgumentException> { SystemicFailureOptions(initialBackoff = 0.seconds) }
        assertThrows<IllegalArgumentException> {
            SystemicFailureOptions(initialBackoff = 2.seconds, maxBackoff = 1.seconds)
        }
        val options = SystemicFailureOptions()
        options.assert().isEqualTo(SystemicFailureOptions()).isNotEqualTo(SystemicFailureOptions(failingAggregates = 4))
        options.hashCode().assert().isEqualTo(SystemicFailureOptions().hashCode())
        options.toString().assert().isEqualTo(
            "SystemicFailureOptions(failingAggregates=3, window=10s, initialBackoff=1s, maxBackoff=30s)"
        )
        options.backoff(1).assert().isEqualTo(1.seconds)
        options.backoff(10).assert().isEqualTo(30.seconds)
        SubscriptionOptions().copyWith(systemic = SystemicFailureOptions(failingAggregates = 0)).systemic
            .failingAggregates.assert().isZero()
    }

    private class MovableClock(private var now: Long = 1_000_000L) : Clock() {
        fun advance(millis: Long) {
            now += millis
        }

        override fun instant(): Instant = Instant.ofEpochMilli(now)

        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this
    }

    private companion object {
        const val WAITS = 20
    }
}
