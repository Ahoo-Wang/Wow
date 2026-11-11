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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.runtime.CommandBusyException
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The rate a partition's timers are sent at: one query a round, a backlog drained at the rate, a slow send's timers
 * kept in the wheel, and the backoff of a send that failed or was refused counted from its end.
 */
class TimerRunnerRateTest : TimerRunnerFixtures() {
    @Test
    fun `one loop reads each store once a round, for every partition it owns, and survives a failed read`(): Unit =
        runBlocking {
            val reads = CopyOnWriteArrayList<Set<Int>>()
            var failing = true
            val counting = object : TimerStore by store {
                override suspend fun due(partitions: Set<Int>, until: Long, limit: Int): List<Timer> {
                    reads += partitions
                    if (failing) {
                        failing = false
                        throw IllegalStateException("The store is unreachable.")
                    }
                    return store.due(partitions, until, limit)
                }
            }
            val other = InMemoryTimerStore()
            val otherReads = CopyOnWriteArrayList<Set<Int>>()
            val counted = object : TimerStore by other {
                override suspend fun due(partitions: Set<Int>, until: Long, limit: Int): List<Timer> {
                    otherReads += partitions
                    return other.due(partitions, until, limit)
                }
            }
            val loop = loop()
            val first = runner(timers = counting, loop = loop)
            val second = runner(timers = counted, loop = loop)
            (0 until 4).forEach { first.startPartition(it) }
            second.startPartition(2)
            store.put(timer("pay", partition = 3))
            other.put(timer("remind", partition = 2))
            eventually { status("pay").status == TimerStatus.FIRED }
            eventually { other.find(order("o-1"), "remind")?.status == TimerStatus.FIRED }
            val rounds = loop.rounds
            rounds.assert().isGreaterThan(1)
            // Never more than one read of a store per round, each of all the partitions it owns there.
            reads.size.toLong().assert().isLessThanOrEqualTo(rounds)
            reads.last().assert().containsExactlyInAnyOrder(0, 1, 2, 3)
            otherReads.size.toLong().assert().isLessThanOrEqualTo(rounds)
            otherReads.last().assert().containsExactly(2)
        }

    @Test
    fun `a backlog of overdue timers drains at the partition's rate`(): Unit = runBlocking {
        val backlog = (1..BACKLOG).map { timer("due-$it") }
        backlog.forEach { store.put(it) }
        clock.advance(10.minutes)
        runner(loop = loop(maxFireRate = RATE)).startPartition(0)
        eventually { fired.size == BACKLOG }
        val spanMillis = (firedAt.max() - firedAt.min()) / 1_000_000
        // BACKLOG firings at RATE a second take at least (BACKLOG - 1) / RATE seconds.
        spanMillis.assert().isGreaterThanOrEqualTo(((BACKLOG - 1) * 1_000 / RATE).toLong() - TOLERANCE)
        eventually { backlog.all { status(it.key).status == TimerStatus.FIRED } }
    }

    @Test
    fun `a slow send keeps the partition's timers in its wheel, and they start at the rate once it ends`(): Unit =
        runBlocking {
            val gate = CompletableDeferred<Unit>().also { gates["due-1"] = it }
            (1..BACKLOG).forEach { store.put(timer("due-$it")) }
            val runner = runner(loop = loop(maxFireRate = RATE, parallelism = 1))
            runner.startPartition(0)
            eventually { fired.size == 1 }
            // Longer than the rate's allowance for every other timer: none of them leaves the wheel meanwhile.
            delay(((BACKLOG * 1_000) / RATE).toLong())
            fired.size.assert().isEqualTo(1)
            runner.loaded(0).assert().isEqualTo(BACKLOG - 1)
            gate.complete(Unit)
            eventually { fired.size == BACKLOG }
            // Each send after the slow one starts at the rate, not at once.
            firedAt.drop(1).zipWithNext { earlier, later -> (later - earlier) / 1_000_000 }
                .assert().allMatch { it >= (1_000 / RATE).toLong() - TOLERANCE }
        }

    @Test
    fun `a failed send is due again its backoff after it failed, however long it took`(): Unit = runBlocking {
        answers["pay"] = IllegalStateException("down")
        takes["pay"] = 5.seconds
        val started = clock.millis()
        store.put(timer("pay"))
        runner().startPartition(0)
        eventually { status("pay").attempts == 1 }
        status("pay").dueAt.assert().isEqualTo(started + 5_000 + 1_000)
        delay(50)
        fired.size.assert().isEqualTo(1)
    }

    @Test
    fun `a busy target's refusal of a slow send is sent again the first backoff after the refusal`(): Unit =
        runBlocking {
            answers["pay"] = CommandBusyException(order("o-1"), "pay", "The node already has 1 commands in flight.")
            takes["pay"] = 5.seconds
            val started = clock.millis()
            store.put(timer("pay"))
            runner().startPartition(0)
            eventually { fired.size == 1 && status("pay").status == TimerStatus.PENDING }
            status("pay").dueAt.assert().isEqualTo(started + 5_000 + 1_000)
            status("pay").attempts.assert().isEqualTo(0)
        }
}
