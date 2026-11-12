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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
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

        // The ten fast entries are complete, counted in the progress: the watermark, which the checkpoint saves, stays
        // below the blocked one.
        eventually { runtime.progress("watermark", 1) == 10L }
        runtime.positions().single().watermark.assert().isEqualTo(1L)
        eventually { checkpoint("watermark") == 1L }

        gate.complete(Unit)
        eventually { checkpoint("watermark") == 11L }
        runtime.stop()
        val saves = checkpoints.savesOf("watermark")
        saves.zipWithNext().all { (a, b) -> a < b }.assert().isTrue()
        saves.last().assert().isEqualTo(11L)
    }

    @Test
    fun `the progress sums the watermarks read here and the checkpoints of the other partitions`(): Unit =
        runBlocking {
            val log = InMemoryEventLog(partitions = 2)
            val appender = Appender(log)
            val runtime = SubscriptionRuntime(
                "group",
                log,
                checkpoints,
                ledger,
                listOf(Subscription("progress", SubscriptionFilter("shop")) {}),
                testOptions(checkpointInterval = 1.hours),
            )
            runtime.progress("unknown", 2).assert().isNull()
            runtime.progress("progress", 2).assert().isEqualTo(0L)
            checkpoints.save("group", "progress", 1, 7)
            runtime.progress("progress", 2).assert().isEqualTo(7L)

            runtime.start(setOf(0))
            repeat(10) { appender.append("a$it") }
            val end = checkNotNull(appender.ends[0])
            // Partition 0's watermark, not yet saved, and partition 1's saved checkpoint.
            eventually { runtime.progress("progress", 2) == end + 7 }
            checkpoints.load("group", "progress", 0).assert().isEqualTo(0L)
            runtime.stop()
            runtime.progress("progress", 2).assert().isEqualTo(end + 7)
        }

    @Test
    fun `the progress reads the checkpoints of the partitions run elsewhere in one call of the store`(): Unit =
        runBlocking {
            // RT-15: a node that runs a third of 64 partitions, as on three nodes; a count query asks for this.
            val counted = ReadCounting(checkpoints)
            checkpoints.save("group", "progress", 40, 7)
            checkpoints.save("group", "progress", 63, 5)
            checkpoints.save("group", "other", 41, 100)
            val runtime = SubscriptionRuntime(
                "group",
                InMemoryEventLog(partitions = 64),
                counted,
                ledger,
                listOf(Subscription("progress", SubscriptionFilter("shop")) {}),
                testOptions(checkpointInterval = 1.hours),
            )
            runtime.start((0 until 21).toSet())
            try {
                counted.reset()
                runtime.progress("progress", 64).assert().isEqualTo(12L)
                counted.loads.get().assert().isZero()
                counted.lists.get().assert().isZero()
                counted.subscriptionLists.get().assert().isEqualTo(1)
            } finally {
                runtime.stop()
            }
        }

    @Test
    fun `a stream is handled once the progress in its partition is past it, the worker's here, else the checkpoint`():
        Unit = runBlocking {
        val handled = Handled()
        val runtime = runtime(Subscription("handled", SubscriptionFilter("shop")) { handled.record(it) })
        appender.append("order")
        val stream = log.read(cart("order")).toList().single()
        // Not read yet, so no checkpoint: not handled.
        runtime.hasHandled("handled", stream).assert().isFalse()

        runtime.start(setOf(0))
        eventually { runtime.hasHandled("handled", stream) }
        // Neither a subscription the runtime does not have, nor one of another context's streams.
        runtime.hasHandled("unknown", stream).assert().isFalse()
        val desk = AggregateType("office", "desk").ref("t", "order")
        val other = EventStreamRecord(desk, 1, "c-d", "r-d", "desk.Act", 0L, stream.events, Scope.NONE)
        runtime.hasHandled("handled", other).assert().isFalse()

        runtime.stop()
        // Stopped: the checkpoint it saved says so.
        runtime.hasHandled("handled", stream).assert().isTrue()
    }

    @Test
    fun `the progress reads no checkpoint when every partition runs here`(): Unit = runBlocking {
        val counted = ReadCounting(checkpoints)
        val runtime = SubscriptionRuntime(
            "group",
            log,
            counted,
            ledger,
            listOf(Subscription("progress", SubscriptionFilter("shop")) {}),
            testOptions(checkpointInterval = 1.hours),
        )
        runtime.start(setOf(0))
        try {
            counted.reset()
            runtime.progress("progress", 1).assert().isEqualTo(0L)
            (counted.loads.get() + counted.lists.get() + counted.subscriptionLists.get()).assert().isZero()
        } finally {
            runtime.stop()
        }
    }

    /** [delegate], counting its reads of checkpoints. */
    private class ReadCounting(private val delegate: CheckpointStore) : CheckpointStore by delegate {
        val loads = AtomicInteger()
        val lists = AtomicInteger()
        val subscriptionLists = AtomicInteger()

        fun reset() {
            loads.set(0)
            lists.set(0)
            subscriptionLists.set(0)
        }

        override suspend fun load(group: String, subscription: String, partition: Int): Long? =
            delegate.load(group, subscription, partition).also { loads.incrementAndGet() }

        override suspend fun list(group: String): List<Checkpoint> =
            delegate.list(group).also { lists.incrementAndGet() }

        override suspend fun list(group: String, subscription: String): List<Checkpoint> =
            delegate.list(group, subscription).also { subscriptionLists.incrementAndGet() }
    }

    @Test
    fun `the progress counts the entries complete above one still running`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val handled = Handled()
        val runtime = runtime(
            Subscription("ahead", SubscriptionFilter("shop")) { entry ->
                if (entry.ref.id == "slow") gate.await()
                handled.record(entry)
            },
            testOptions(checkpointInterval = 1.hours),
        )
        appender.append("slow") // 1: blocks
        repeat(3) { appender.append("fast") } // 2..4
        runtime.start(setOf(0))
        eventually { runtime.progress("ahead", 1) == 3L }
        runtime.positions().single().watermark.assert().isEqualTo(0L)
        gate.complete(Unit)
        eventually { runtime.progress("ahead", 1) == 4L }
        runtime.stop()
        handled.size.assert().isEqualTo(4)
    }

    @Test
    fun `the periodic save stores every moved checkpoint in one call, and a failed one again on the next tick`(): Unit =
        runBlocking {
            // B.D diagnostics: one round trip a tick for all the subscriptions and partitions, not one each.
            val log = InMemoryEventLog(partitions = 4)
            val appender = Appender(log)
            // The store fails once the partitions have started, whose first checkpoints it saves in one call each.
            val failures = AtomicInteger(0)
            val failingOnce = object : CheckpointStore by checkpoints {
                override suspend fun saveAll(group: String, checkpoints: List<Checkpoint>) {
                    check(failures.getAndDecrement() <= 0) { "The store is down." }
                    this@SubscriptionCheckpointTest.checkpoints.saveAll(group, checkpoints)
                }
            }
            val runtime = SubscriptionRuntime(
                "group",
                log,
                failingOnce,
                ledger,
                listOf(
                    Subscription("one", SubscriptionFilter("shop")) {},
                    Subscription("two", SubscriptionFilter("shop")) {}
                ),
                testOptions(checkpointInterval = 200.milliseconds),
            )
            repeat(30) { appender.append("a$it") }
            runtime.start(setOf(0, 1, 2, 3))
            failures.set(1)
            eventually {
                (0..3).all { partition ->
                    listOf("one", "two").all { checkpoints.load("group", it, partition) == appender.ends[partition] }
                }
            }
            runtime.stop()
            failures.get().assert().isLessThan(0)
            checkpoints.batches.assert().startsWith(2, 2, 2, 2).contains(8)
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
    fun `a subscription from the end of an empty partition handles its first entries after a kill before any save`():
        Unit = runBlocking {
        // G33: a node killed before its first periodic save saves nothing more; its successor starts from the store.
        val killable = object : CheckpointStore by checkpoints {
            @Volatile
            var killed = false

            override suspend fun save(group: String, subscription: String, partition: Int, position: Long) {
                if (!killed) checkpoints.save(group, subscription, partition, position)
            }

            // A stop saves its checkpoints in one call.
            override suspend fun saveAll(group: String, checkpoints: List<Checkpoint>) {
                if (!killed) this@SubscriptionCheckpointTest.checkpoints.saveAll(group, checkpoints)
            }
        }
        val first = Handled()
        val killedNode = SubscriptionRuntime(
            "group",
            log,
            killable,
            ledger,
            listOf(Subscription("saga", SubscriptionFilter("shop"), StartFrom.END) { first.record(it) }),
            testOptions(checkpointInterval = 1.hours),
        )
        killedNode.start(setOf(0))
        // The start, 0 in an empty partition, is saved at once.
        checkpoint("saga").assert().isEqualTo(0L)
        repeat(2) { appender.append("order") }
        eventually { first.size == 2 }
        killable.killed = true
        killedNode.stop()
        checkpoint("saga").assert().isEqualTo(0L)

        // The next owner starts at the saved 0, not at the log's end, 2 by now: nothing committed meanwhile is lost.
        appender.append("order")
        val next = Handled()
        val successor = runtime(Subscription("saga", SubscriptionFilter("shop"), StartFrom.END) { next.record(it) })
        successor.start(setOf(0))
        eventually { next.size == 3 }
        successor.stop()
        next.sequences().assert().isEqualTo(listOf(1L, 2L, 3L))
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

    @Test
    fun `subscriptions that start where the service joins a stream read it from the beginning on its first join`():
        Unit = runBlocking {
        repeat(4) { appender.append("old") }
        val saga = Handled()
        val reactor = Handled()
        val joining = listOf(
            Subscription("saga", SubscriptionFilter("shop"), StartFrom.JOIN) { saga.record(it) },
            Subscription("reactor", SubscriptionFilter("shop"), StartFrom.JOIN) { reactor.record(it) },
        )
        SubscriptionRuntime("group", log, checkpoints, ledger, joining, testOptions()).apply {
            start(setOf(0))
            eventually { saga.size == 4 && reactor.size == 4 }
            stop()
        }
        saga.sequences().assert().isEqualTo((1L..4L).toList())
        reactor.sequences().assert().isEqualTo((1L..4L).toList())
    }

    @Test
    fun `a subscription added to a service that reads the stream starts after what it committed before`():
        Unit = runBlocking {
        repeat(4) { appender.append("old") }
        // An earlier subscription of the service read the stream, even one since removed from the code.
        checkpoints.save("group", "removed", 0, 4)
        val added = Handled()
        val runtime = runtime(Subscription("added", SubscriptionFilter("shop"), StartFrom.JOIN) { added.record(it) })
        runtime.start(setOf(0))
        checkpoint("added").assert().isEqualTo(4L)
        repeat(2) { appender.append("new") }
        eventually { added.size == 2 }
        runtime.stop()
        added.sequences().assert().isEqualTo(listOf(5L, 6L))
    }

    @Test
    fun `a subscription added to a running runtime starts where the service joined the stream`(): Unit = runBlocking {
        repeat(3) { appender.append("old") }
        val first = Handled()
        val runtime = runtime(Subscription("first", SubscriptionFilter("shop"), StartFrom.JOIN) { first.record(it) })
        runtime.start(setOf(0))
        eventually { first.size == 3 }
        val later = Handled()
        runtime.add(Subscription("later", SubscriptionFilter("shop"), StartFrom.JOIN) { later.record(it) })
        checkpoint("later").assert().isEqualTo(3L)
        appender.append("new")
        eventually { later.size == 1 && first.size == 4 }
        runtime.stop()
        later.sequences().assert().isEqualTo(listOf(4L))
    }
}
