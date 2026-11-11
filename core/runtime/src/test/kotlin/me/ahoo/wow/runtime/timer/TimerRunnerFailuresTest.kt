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

package me.ahoo.wow.runtime.timer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.CommandBusyException
import me.ahoo.wow.runtime.health.NodeFatals
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.observation.ObservedEvent.Fatal.Component
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * What a send that fails does to its timer: a busy target as backpressure, an error or a cancellation as a failed
 * attempt, a fatal error as the node's, and a stack overflow as neither, inside the sending or outside it.
 */
class TimerRunnerFailuresTest : TimerRunnerFixtures() {
    @Test
    fun `a busy target is backpressure, sent again without spending an attempt`(): Unit = runBlocking {
        // Review R2.1-1: the node's in-flight bound never parks a timer.
        answers["pay"] = CommandBusyException(order("o-1"), "pay", "The node already has 1 commands in flight.")
        store.put(timer("pay"))
        runner().startPartition(0)
        repeat(5) { round ->
            eventually { fired.size == round + 1 && status("pay").status == TimerStatus.PENDING }
            status("pay").attempts.assert().isEqualTo(0)
            status("pay").dueAt.assert().isEqualTo(clock.millis() + 1_000)
            clock.advance(1.seconds)
        }
        ledger.parked(SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION), order("o-1")).assert().isNull()
        answers.remove("pay")
        eventually { status("pay").status == TimerStatus.FIRED }
        status("pay").attempts.assert().isEqualTo(1)
    }

    @Test
    fun `a target that stays busy spends an attempt after each run of busy retries, and parks the timer`(): Unit =
        runBlocking {
            answers["pay"] = CommandBusyException(order("o-1"), "pay", "The node already has 1 commands in flight.")
            store.put(timer("pay"))
            runner(loop = loop(maxAttempts = 3, maxBusyRetries = 2)).startPartition(0)
            // Two busy retries, then the third refusal spends an attempt; twice more, and the third attempt parks it.
            listOf(0, 0, 1, 1, 1, 2, 2, 2).forEachIndexed { round, attempts ->
                eventually { fired.size == round + 1 && status("pay").status == TimerStatus.PENDING }
                status("pay").attempts.assert().isEqualTo(attempts)
                clock.advance(2.seconds)
            }
            eventually { status("pay").status == TimerStatus.PARKED }
            fired.size.assert().isEqualTo(9)
            status("pay").attempts.assert().isEqualTo(3)
            eventually { ledger.parked(SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION), order("o-1")) != null }
        }

    @Test
    fun `an error or a cancellation the sending throws itself is a failed attempt, retried`(): Unit = runBlocking {
        // Finding A1-12: neither may leave the timer stuck as being sent.
        answers["pay"] = NoClassDefFoundError("shop/CancelOrder")
        answers["remind"] = CancellationException("the target's own timeout")
        store.put(timer("pay"))
        store.put(timer("remind"))
        runner().startPartition(0)
        eventually { status("pay").attempts == 1 && status("remind").attempts == 1 }
        status("pay").status.assert().isEqualTo(TimerStatus.PENDING)
        status("pay").outcome.assert().isEqualTo("java.lang.NoClassDefFoundError: shop/CancelOrder")
        status("remind").status.assert().isEqualTo(TimerStatus.PENDING)
        answers.clear()
        clock.advance(1.seconds)
        eventually { status("pay").status == TimerStatus.FIRED && status("remind").status == TimerStatus.FIRED }
    }

    @Test
    fun `a fatal error is the node's, its timer sent again by the partition's next owner, never here`(): Unit =
        runBlocking {
            // X-80: nothing retries it in this process; the claim lapses for the next owner.
            val fatals = NodeFatals("node-1")
            answers["pay"] = OutOfMemoryError("not really")
            store.put(timer("pay"))
            val runner = runner(loop = loop(fatals = fatals))
            runner.startPartition(0)
            eventually { fired.size == 1 && fatals.isFatal }
            fatals.first!!.component.assert().isEqualTo(Component.TIMER)
            fatals.first!!.name.assert().isEqualTo(SHOP)
            fatals.first!!.partition.assert().isEqualTo(0)
            status("pay").status.assert().isEqualTo(TimerStatus.FIRING)
            answers.clear()
            clock.advance(CLAIM)
            // Three rounds, not two: a send dispatched in a round runs in its own coroutine, which records it
            // maybe after the next round starts.
            rounds(3)
            fired.size.assert().isEqualTo(1)
            ledger.parked(SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION), order("o-1")).assert().isNull()
            // Handed off: the partition's next owner sends it once the claim lapsed.
            runner.stopPartition(0)
            runner(loop = loop()).startPartition(0)
            eventually { status("pay").status == TimerStatus.FIRED }
            fired.size.assert().isEqualTo(2)
        }

    @Test
    fun `a stack overflow the sending throws is a failed attempt, retried, never the node's`(): Unit = runBlocking {
        val fatals = NodeFatals("node-1")
        answers["pay"] = StackOverflowError()
        store.put(timer("pay"))
        runner(loop = loop(fatals = fatals)).startPartition(0)
        eventually { status("pay").attempts == 1 }
        status("pay").status.assert().isEqualTo(TimerStatus.PENDING)
        status("pay").outcome.assert().startsWith(StackOverflowError::class.java.name)
        answers.clear()
        clock.advance(1.seconds)
        eventually { status("pay").status == TimerStatus.FIRED }
        fatals.isFatal.assert().isFalse()
    }

    @Test
    fun `a stack overflow outside the sending leaves the timer to the next load, never the node's`(): Unit =
        runBlocking {
            val fatals = NodeFatals("node-1")
            val overflowing = AtomicBoolean(true)
            val claiming = object : TimerStore by store {
                override suspend fun settle(changes: List<TimerChange>): Int {
                    if (overflowing.compareAndSet(true, false)) throw StackOverflowError()
                    return store.settle(changes)
                }
            }
            store.put(timer("pay"))
            runner(timers = claiming, loop = loop(fatals = fatals)).startPartition(0)
            eventually { status("pay").status == TimerStatus.FIRED }
            fired.assert().containsExactly(timer("pay").id)
            fatals.isFatal.assert().isFalse()
        }
}
