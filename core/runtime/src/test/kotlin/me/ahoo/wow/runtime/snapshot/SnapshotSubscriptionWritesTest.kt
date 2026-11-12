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
import org.junit.jupiter.params.provider.ValueSource

/**
 * How the snapshot subscription writes: one aggregate's waiting entries once at the latest version, the snapshots built
 * while a write runs together, every partition's sharing one queue and at most two writes at once, and an entry made by
 * hand.
 */
class SnapshotSubscriptionWritesTest : SnapshotSubscriptionFixtures() {
    @Test
    fun `one aggregate's entries waiting together are written once, at the latest version`(): Unit = runBlocking {
        runtime.start(setOf(0))
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        val first = async { coordinator.execute(counter, add("a")) }
        eventually { store.saves.size == 1 }
        // Versions 2 to 5 queue behind version 1, whose write is held.
        val later = (2..5).map { async { coordinator.execute(counter, add("a")) } }
        eventually { probed.count { it.first == "a" } == 5 }
        later.forEach { it.isCompleted.assert().isFalse() }
        gate.complete(Unit)

        first.await().hop.assert().isEqualTo(Hop.snapshot("shop"))
        later.map { it.await() }.forEach {
            it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            it.hop.assert().isEqualTo(Hop.snapshot("shop"))
        }
        store.saves.assert().containsExactly(1, 5)
        store.load(ref("a"))!!.state.assert().isEqualTo("""{"total":5}""")
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
            // The second committer's write took those ready when it gathered, and the next write the rest: three in all.
            store.batches.size.assert().isEqualTo(3)
            store.batches.first().assert().isEqualTo(1)
            store.batches.sum().assert().isEqualTo(1 + ids.size)
            ids.forEach { store.load(ref(it))!!.version.assert().isEqualTo(1) }
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
        ids.forEach { store.load(ref(it))!!.version.assert().isEqualTo(1) }
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 4])
    fun `the writes in flight reach the store's count under a queue and never exceed it`(inFlight: Int): Unit =
        runBlocking {
            val counted = RecordingStore(inFlight = inFlight)
            val subscription = SnapshotSubscription("shop", listOf(counter), counted, kernel, log, codec).subscription
            val ids = (0..inFlight).map { "w$it" }
            ids.forEach { executor.execute(counter, command(it, Add())) as CommandOutcome.Committed }
            fun entry(id: String) = async {
                val stream = log.read(ref(id)).toList().single()
                subscription.handle(subscription.decoder.decode(stream, LogPosition(0, ids.indexOf(id) + 1L), null))
            }
            val gate = CompletableDeferred<Unit>().also { counted.gate = it }
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
