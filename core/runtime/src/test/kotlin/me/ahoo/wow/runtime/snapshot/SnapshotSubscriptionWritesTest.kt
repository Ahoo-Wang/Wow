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

/**
 * How the snapshot subscription writes: one aggregate's waiting entries once at the latest version, a partition's built
 * while a write runs together, a lane's partitions sharing the writes, other lanes at once, and an entry made by hand.
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
        delay(50)
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
            delay(50)
            // No wait is answered before the write that covers it.
            (listOf(first) + others).forEach { it.isCompleted.assert().isFalse() }
            store.gate = null
            gate.complete(Unit)
            (listOf(first) + others).map { it.await() }.forEach {
                it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                it.hop.assert().isEqualTo(Hop.snapshot("shop"))
            }
            store.batches.assert().containsExactly(1, ids.size)
            ids.forEach { store.load(ref(it))!!.version.assert().isEqualTo(1) }
        }

    @Test
    fun `the snapshots of the partitions of one lane share the writes`(): Unit = runBlocking {
        // B.O-5: partitions 0, 4 and 8 share a writer (p % 4), so a write holds more than one partition's snapshot.
        listOf("p0", "p1", "p2").forEach { executor.execute(counter, command(it, Add())) as CommandOutcome.Committed }
        fun entry(id: String, partition: Int) = async {
            val stream = log.read(ref(id)).toList().single()
            snapshots.subscription.handle(
                snapshots.subscription.decoder.decode(stream, LogPosition(partition, 1), null)
            )
        }
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        val first = entry("p0", 0)
        eventually { store.batches.size == 1 }
        // Partitions 4 and 8 wait for the write partition 0 leads, and are written together after it.
        val others = listOf(entry("p1", 4), entry("p2", 8))
        delay(50)
        store.batches.size.assert().isEqualTo(1)
        store.gate = null
        gate.complete(Unit)
        (listOf(first) + others).awaitAll()
        store.batches.assert().containsExactly(1, 2)
        listOf("p0", "p1", "p2").forEach { store.load(ref(it))!!.version.assert().isEqualTo(1) }
    }

    @Test
    fun `the partitions of other lanes write at once`(): Unit = runBlocking {
        listOf("q0", "q1").forEach { executor.execute(counter, command(it, Add())) as CommandOutcome.Committed }
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        val handled = listOf("q0" to 0, "q1" to 1).map { (id, partition) ->
            async {
                val stream = log.read(ref(id)).toList().single()
                snapshots.subscription.handle(
                    snapshots.subscription.decoder.decode(stream, LogPosition(partition, 1), null)
                )
            }
        }
        // Partition 1's lane does not wait for partition 0's write.
        eventually { store.batches.size == 2 }
        store.gate = null
        gate.complete(Unit)
        handled.awaitAll()
        store.batches.assert().containsExactly(1, 1)
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
