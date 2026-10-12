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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryFailureLedger
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test

class SharedTailTest {
    private val log = CountingLog(InMemoryEventLog(partitions = 4))
    private val appender = Appender(log)
    private val checkpoints = RecordingCheckpoints()
    private val ledger = InMemoryFailureLedger()

    private fun runtime(subscriptions: List<Subscription>, options: SubscriptionOptions = testOptions()) =
        SubscriptionRuntime("group", log, checkpoints, ledger, subscriptions, options)

    private suspend fun appendAcross(count: Int) = repeat(count) { appender.append("a${it % 16}") }

    /** The tail reads opened to deliver 200 entries to [subscriptionCount] subscriptions over 4 partitions. */
    private fun readsFor(subscriptionCount: Int): Int = runBlocking {
        val handled = List(subscriptionCount) { Handled() }
        val runtime = runtime(
            handled.mapIndexed { i, h -> Subscription("s$i", SubscriptionFilter("shop")) { h.record(it) } }
        )
        appendAcross(100)
        runtime.start(setOf(0, 1, 2, 3))
        appendAcross(100)
        eventually { handled.all { it.size == 200 } }
        runtime.stop()
        handled.forEach { h -> h.entries.map { it.first to it.second }.toSet().size.assert().isEqualTo(200) }
        log.tails.get()
    }

    @Test
    fun `the aggregates of these tests cover every partition`() {
        (0 until 16).map { Partitioner.partitionOf(cart("a$it"), 4) }.toSet().assert().isEqualTo(setOf(0, 1, 2, 3))
    }

    @Test
    fun `one subscription reads each partition once`() {
        readsFor(1).assert().isEqualTo(4)
    }

    @Test
    fun `twenty subscriptions still read each partition once`() {
        readsFor(20).assert().isEqualTo(4)
    }

    @Test
    fun `a late subscription catches up on its own, then joins the shared tail`(): Unit = runBlocking {
        val early = Handled()
        val runtime = runtime(listOf(Subscription("early", SubscriptionFilter("shop")) { early.record(it) }))
        appendAcross(100)
        runtime.start(setOf(0, 1, 2, 3))
        eventually { early.size == 100 }
        log.tails.get().assert().isEqualTo(4)

        val late = Handled()
        val writer = launch(Dispatchers.Default) {
            repeat(100) {
                appender.append("a${it % 16}")
                yield()
            }
        }
        runtime.add(Subscription("late", SubscriptionFilter("shop")) { late.record(it) })
        writer.join()
        eventually { late.size == 200 && early.size == 200 }
        runtime.stop()

        // One catch-up read per partition, then the shared tail; nothing missed, nothing twice.
        log.tails.get().assert().isEqualTo(8)
        for (h in listOf(early, late)) {
            (0 until 16).forEach { h.versionsOf("a$it").assert().isEqualTo((1..h.versionsOf("a$it").size).toList()) }
            h.entries.map { it.first to it.second }.toSet().size.assert().isEqualTo(200)
        }
    }

    @Test
    fun `subscriptions behind at start catch up while the others follow the shared tail`(): Unit = runBlocking {
        appendAcross(100)
        // "ahead" gets a checkpoint at the end of every partition; "behind" has none.
        val warmup = Handled()
        runtime(listOf(Subscription("ahead", SubscriptionFilter("shop")) { warmup.record(it) })).apply {
            start(setOf(0, 1, 2, 3))
            eventually { warmup.size == 100 }
            stop()
        }
        val readsBefore = log.tails.get()

        val ahead = Handled()
        val behind = Handled()
        val runtime = runtime(
            listOf(
                Subscription("ahead", SubscriptionFilter("shop")) { ahead.record(it) },
                Subscription("behind", SubscriptionFilter("shop")) { behind.record(it) },
            ),
        )
        runtime.start(setOf(0, 1, 2, 3))
        appendAcross(20)
        eventually { ahead.size == 20 && behind.size == 120 }
        runtime.stop()
        (log.tails.get() - readsBefore).assert().isEqualTo(8)
        ahead.size.assert().isEqualTo(20)
    }

    @Test
    fun `a slow subscription is detached instead of holding up the group`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val slow = Handled()
        val fast = Handled()
        val runtime = runtime(
            listOf(
                Subscription("slow", SubscriptionFilter("shop")) { entry ->
                    if (entry.ref.id == "a0" && entry.version == 1) gate.await()
                    slow.record(entry)
                },
                Subscription("fast", SubscriptionFilter("shop")) { fast.record(it) },
            ),
            testOptions(maxInFlight = 4),
        )
        runtime.start(setOf(0, 1, 2, 3))
        appendAcross(160)
        val blocked = Partitioner.partitionOf(cart("a0"), 4)
        fun inBlocked(id: String) = Partitioner.partitionOf(cart(id), 4) == blocked
        val elsewhere = (0 until 16).count { !inBlocked("a$it") } * 10
        eventually { fast.size == 160 }
        eventually { slow.entries.count { !inBlocked(it.first) } == elsewhere }
        // In the blocked partition the slow subscription holds at most 4 entries, one of them the blocked one.
        slow.entries.count { inBlocked(it.first) }.assert().isLessThanOrEqualTo(3)
        gate.complete(Unit)
        eventually { slow.size == 160 }
        runtime.stop()
        slow.entries.map { it.first to it.second }.toSet().size.assert().isEqualTo(160)
        (0 until 16).forEach { slow.versionsOf("a$it").assert().isEqualTo((1..10).toList()) }
    }
}
