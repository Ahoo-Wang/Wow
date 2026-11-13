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

package me.ahoo.wow.runtime.snapshot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.LogPosition
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * How the snapshot subscription writes: one aggregate's waiting entries once at the latest version, the snapshots built
 * while a write runs together, every partition's sharing one queue and at most two writes at once, and an entry made by
 * hand.
 */
class SnapshotSubscriptionWritesTest : SnapshotSubscriptionFixtures() {
    @Test
    fun `one aggregate's entries waiting together are written once, at the latest version`(): Unit = runBlocking {
        val counts = snapshots.writeCounts()
        snapshots.writeCounts().assert().isSameAs(counts)
        runtime.start(setOf(0))
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        val first = async { coordinator.execute(counter, add("a")) }
        eventually { store.saves.size == 1 }
        // Versions 2 to 5 queue behind version 1, whose write is held.
        val later = (2..5).map { async { coordinator.execute(counter, add("a")) } }
        // What makes [1, 5] certain: the snapshot subscription itself holds all five before the write is let go, so
        // the lane takes 2 to 5 together once 1 is written. The probe, another subscription, may run ahead of it.
        eventually { snapshotsTaken() == 5 }
        later.forEach { it.isCompleted.assert().isFalse() }
        gate.complete(Unit)

        first.await().hop.assert().isEqualTo(Hop.snapshot("shop"))
        later.map { it.await() }.forEach {
            it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            it.hop.assert().isEqualTo(Hop.snapshot("shop"))
        }
        store.saves.assert().containsExactly(1, 5)
        store.load(ref("a"))!!.state.assert().isEqualTo("""{"total":5}""")
        // The subscription's writes counted, from its first read: two writes of one save each, the second the four
        // entries' latest (the same certainty as the saves above); each is clocked once its saves ended: waited for.
        eventually { counts.clock.calls == 2L }
        counts.saves.assert().isEqualTo(2L)
        counts.snapshots.assert().isEqualTo(2L)
    }

    @Test
    fun `the snapshots of a partition's aggregates built while a write runs are written together`(): Unit =
        runBlocking {
            runtime.start(setOf(0))
            val gate = CompletableDeferred<Unit>().also { store.gate = it }
            val first = async { coordinator.execute(counter, add("a")) }
            eventually { store.batches.size == 1 }
            // Eight other aggregates commit while a's write is held: their snapshots wait for the next write.
            val ids = (1..8).map { "b$it" }
            val others = ids.map { async { coordinator.execute(counter, add(it)) } }
            eventually { ids.all { id -> probed.contains(id to 1) } }
            // No wait is answered before the write that covers it.
            (listOf(first) + others).forEach { it.isCompleted.assert().isFalse() }
            store.gate = null
            gate.complete(Unit)
            (listOf(first) + others).map { it.await() }.forEach {
                it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                it.hop.assert().isEqualTo(Hop.snapshot("shop"))
            }
            // What the design promises, and nothing a scheduler decides. How many writes carried the eight depends on
            // how many had reached the queue when each committer gathered: their subscription runs on the Default
            // dispatcher's threads, the probe's wait above sees another subscription. How saves merge is pinned where
            // the gathering is deterministic, in SnapshotWritesTest. Here: a's write alone first, every snapshot
            // written once at its version, never more writes at once than the store's count.
            store.batches.first().assert().isEqualTo(1)
            store.batches.sum().assert().isEqualTo(1 + ids.size)
            ids.forEach { store.load(ref(it))!!.version.assert().isEqualTo(1) }
            store.peakWrites.get().assert().isLessThanOrEqualTo(store.writesInFlight)
        }

    @Test
    fun `every partition's snapshots share one queue, at most two writes at once`(): Unit = runBlocking {
        val ids = listOf("p0", "p1", "p2", "p3")
        ids.forEach { executor.execute(counter, command(it, Add())) as CommandOutcome.Committed }
        fun entry(id: String, partition: Int) = async {
            val stream = log.read(ref(id)).toList().single()
            snapshots.subscription.handle(
                snapshots.subscription.decoder.decode(stream, LogPosition(partition, 1), null)
            )
        }
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        // What makes the exact batches certain: every entry is handled here, on runBlocking's one thread, coroutines
        // run in the order they are started and nothing on the snapshot path changes dispatcher, so which saves are
        // queued when a write gathers is the code's order, not a scheduler's.
        // Partition 0's write runs, held; partition 1's starts the second, whatever partitions they are of.
        val first = entry("p0", 0)
        eventually { store.batches.size == 1 }
        val second = listOf(entry("p1", 1), entry("p2", 2))
        eventually { store.batches.size == 2 }
        // Two writes run: partition 3's snapshot waits for one of them to end.
        val last = entry("p3", 3)
        // A time, not an event: a third write started wrongly shows only after its gathering's yield.
        delay(50)
        store.batches.size.assert().isEqualTo(2)
        store.gate = null
        gate.complete(Unit)
        (listOf(first) + second + last).awaitAll()
        store.batches.first().assert().isEqualTo(1)
        store.batches.sum().assert().isEqualTo(ids.size)
        store.batches.size.assert().isEqualTo(3)
        store.peakWrites.get().assert().isEqualTo(2)
        ids.forEach { store.load(ref(it))!!.version.assert().isEqualTo(1) }
    }

    @ParameterizedTest
    @CsvSource("2,", "4,", "2,3", "4,1")
    fun `the writes in flight reach the store's count, or the override over it, under a queue and never exceed it`(
        storeCount: Int,
        override: Int?,
    ): Unit =
        runBlocking {
            val counted = RecordingStore(inFlight = storeCount)
            val inFlight = override ?: storeCount
            val subscription = SnapshotSubscription(
                "shop",
                listOf(counter),
                counted,
                kernel,
                log,
                codec,
                writesInFlight = override,
            ).subscription
            val ids = (0..inFlight).map { "w$it" }
            ids.forEach { executor.execute(counter, command(it, Add())) as CommandOutcome.Committed }
            fun entry(id: String) = async {
                val stream = log.read(ref(id)).toList().single()
                subscription.handle(subscription.decoder.decode(stream, LogPosition(0, ids.indexOf(id) + 1L), null))
            }
            val gate = CompletableDeferred<Unit>().also { counted.gate = it }
            // Certain for the same reason as above: one thread, coroutines in start order, no change of dispatcher.
            // Each entry, arriving once the writes before took their batches, starts one more write: the store's count.
            val held = ids.take(inFlight).mapIndexed { started, id ->
                entry(id).also { eventually { counted.batches.size == started + 1 } }
            }
            // One more waits for a write to end.
            val waiting = entry(ids.last())
            // A time, not an event: a write started wrongly shows only after its gathering's yield.
            delay(50)
            counted.batches.size.assert().isEqualTo(inFlight)
            counted.gate = null
            gate.complete(Unit)
            (held + waiting).awaitAll()
            counted.batches.assert().containsExactly(*Array(inFlight + 1) { 1 })
            counted.peakWrites.get().assert().isEqualTo(inFlight)
        }

    @Test
    fun `an entry made by hand, without a position, is written with partition 0's snapshots`(): Unit = runBlocking {
        executor.execute(counter, command("h", Add())) as CommandOutcome.Committed
        val stream = log.read(ref("h")).toList().single()
        val entry = snapshots.subscription.decoder.decode(stream, null, null)
        snapshots.subscription.handle(entry)
        store.load(ref("h"))!!.version.assert().isEqualTo(1)
    }
}
