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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryFailureLedger
import me.ahoo.wow.kernel.StartFrom
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class SubscriptionCheckpointTest {
    private val log = InMemoryEventLog(partitions = 1)
    private val appender = Appender(log)
    private val checkpoints = RecordingCheckpoints()
    private val ledger = InMemoryFailureLedger()

    private fun runtime(subscription: Subscription, options: SubscriptionOptions = testOptions()) =
        SubscriptionRuntime("group", log, checkpoints, ledger, listOf(subscription), options)

    private suspend fun checkpoint(subscription: String): Long? = checkpoints.load("group", subscription, 0)

    @Test
    fun `the checkpoint is the contiguous low watermark and only moves forward`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val handled = Handled()
        val runtime = runtime(
            Subscription("watermark", SubscriptionFilter("shop")) { entry ->
                if (entry.ref.id == "slow") gate.await()
                handled.record(entry)
            },
        )
        appender.append("fast") // 1
        appender.append("slow") // 2: blocks
        repeat(9) { appender.append("fast") } // 3..11
        runtime.start(setOf(0))

        eventually { handled.versionsOf("fast").size == 10 }
        eventually { checkpoint("watermark") == 1L }
        delay(50.milliseconds) // several checkpoint intervals: the entries above the blocked one do not count
        checkpoint("watermark").assert().isEqualTo(1L)

        gate.complete(Unit)
        eventually { checkpoint("watermark") == 11L }
        runtime.stop()
        val saves = checkpoints.savesOf("watermark")
        saves.zipWithNext().all { (a, b) -> a < b }.assert().isTrue()
        saves.last().assert().isEqualTo(11L)
    }

    @Test
    fun `stop waits for the entries in flight and saves the checkpoint`(): Unit = runBlocking {
        val handled = Handled()
        val runtime = runtime(
            Subscription("stopping", SubscriptionFilter("shop")) { entry ->
                delay(1)
                handled.record(entry)
            },
            testOptions(checkpointInterval = 1.hours),
        )
        repeat(30) { appender.append("a${it % 3}") }
        runtime.start(setOf(0))
        eventually { handled.size >= 1 }
        runtime.stop()
        runtime.isRunning.assert().isFalse()

        checkpoint("stopping").assert().isEqualTo(handled.sequences().max())
        handled.sequences().sorted().assert().isEqualTo((1L..handled.sequences().max()).toList())
    }

    @Test
    fun `a restart resumes from the checkpoint without losing an entry`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val first = Handled()
        val firstRun = runtime(
            Subscription("restart", SubscriptionFilter("shop")) { entry ->
                if (entry.ref.id == "slow") gate.await()
                first.record(entry)
            },
            testOptions(stopTimeout = 20.milliseconds),
        )
        repeat(5) { appender.append("fast") } // 1..5
        appender.append("slow") // 6: never completes in the first run
        repeat(5) { appender.append("fast") } // 7..11
        firstRun.start(setOf(0))
        eventually { first.versionsOf("fast").size == 10 }
        firstRun.stop() // abandons the slow entry
        val saved = checkNotNull(checkpoint("restart"))
        saved.assert().isEqualTo(5L)
        repeat(3) { appender.append("fast") } // 12..14, committed while stopped

        val second = Handled()
        val secondRun = runtime(Subscription("restart", SubscriptionFilter("shop")) { second.record(it) })
        secondRun.start(setOf(0))
        eventually { checkpoint("restart") == 14L }
        secondRun.stop()

        (first.sequences() + second.sequences()).toSet().assert().isEqualTo((1L..14L).toSet())
        // Nothing at or below the checkpoint is handled again; duplicates only above it.
        second.sequences().all { it > saved }.assert().isTrue()
        second.sequences().sorted().assert().isEqualTo((6L..14L).toList())
    }

    @Test
    fun `a subscription from the end skips what was committed before it first started`(): Unit = runBlocking {
        repeat(10) { appender.append("old") }
        val handled = Handled()
        val subscription = Subscription("tail", SubscriptionFilter("shop"), StartFrom.END) { handled.record(it) }
        val runtime = runtime(subscription)
        runtime.start(setOf(0))
        checkpoint("tail").assert().isEqualTo(10L) // saved at once
        repeat(5) { appender.append("new") }
        eventually { handled.size == 5 }
        runtime.stop()
        handled.sequences().assert().isEqualTo((11L..15L).toList())

        // Restarted, it continues from its checkpoint rather than from a new end.
        repeat(2) { appender.append("new") }
        val again = Handled()
        val restarted = runtime(Subscription("tail", SubscriptionFilter("shop"), StartFrom.END) { again.record(it) })
        restarted.start(setOf(0))
        eventually { again.size == 2 }
        restarted.stop()
        again.sequences().assert().isEqualTo(listOf(16L, 17L))
    }

    @Test
    fun `a replay is a new subscription from the beginning`(): Unit = runBlocking {
        repeat(8) { appender.append("a") }
        val v1 = Handled()
        runtime(Subscription("projection-v1", SubscriptionFilter("shop")) { v1.record(it) }).apply {
            start(setOf(0))
            eventually { v1.size == 8 }
            stop()
        }
        val v2 = Handled()
        runtime(Subscription("projection-v2", SubscriptionFilter("shop")) { v2.record(it) }).apply {
            start(setOf(0))
            eventually { v2.size == 8 }
            stop()
        }
        v2.versionsOf("a").assert().isEqualTo((1..8).toList())
        checkpoint("projection-v1").assert().isEqualTo(8L)
        checkpoint("projection-v2").assert().isEqualTo(8L)
    }
}
