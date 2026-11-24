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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.snapshot.SnapshotFill
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours

/**
 * Which nodes backfill a snapshot store ([SnapshotMaintenance.prepare]): none on a cluster started on fresh stores,
 * every one, for its partitions, on a store its subscription is already past the beginning of; and a backfill stops on
 * its table's mark, whichever node wrote it.
 */
class SnapshotBackfillTest {
    data class Add(val amount: Int)
    data class Added(val amount: Int)

    class Counter {
        var total = 0
    }

    private val model: AggregateModel<Counter> = aggregate<Counter>(CONTEXT, "counter") {
        initial { Counter() }
        decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.amount)) }
        evolve<Added> { total += it.amount }
    }

    private val log = InMemoryEventLog(PARTITIONS)
    private val codec = EventCodec()
    private val kernel = Kernel(log, codec)
    private val leases = InMemoryLeaseStore()
    private val checkpoints = InMemoryCheckpointStore()
    private val ledger = InMemoryFailureLedger()
    private val memory = InMemorySnapshotStore()
    private val type = AggregateType(CONTEXT, "counter")
    private val primary = SnapshotTargets.single(memory).subscriptionOf(SnapshotTargets.DEFAULT_STORE)
    private val backfill = "$primary#counter"

    private fun maintenance(node: String, store: SnapshotStore = memory) = SnapshotMaintenance(
        CONTEXT,
        GROUP,
        node,
        listOf(model),
        SnapshotTargets.single(store),
        kernel,
        codec,
        log,
        leases,
        listOf(LogProgress(GROUP, log, checkpoints, ledger)),
        SnapshotOptions(interval = 1.hours, leaseTtl = 2.hours, memberTtl = 2.hours),
    )

    private suspend fun commit(id: String): AggregateRef {
        val ref = type.ref("tenant", id)
        kernel.execute(model, Command("c-$id", "r-$id", ref, Add(1)))
        return ref
    }

    /** [subscription]'s checkpoint in every partition at [at]: what a runtime saves as it starts it, or later. */
    private suspend fun checkpointed(subscription: String, at: (Int) -> Long = { 0L }) {
        (0 until PARTITIONS).forEach { checkpoints.save(GROUP, subscription, it, at(it)) }
    }

    private suspend fun head(partition: Int): Long = checkNotNull(log.head(partition))

    @Test
    fun `a cluster started on fresh stores runs no backfill, the first node marking the table for the others`(): Unit =
        runBlocking {
            maintenance("node-1").prepare().assert().isEmpty()
            memory.table(type)!!.backfilled.assert().isTrue()
            // The first node's runtime starts: its starting checkpoints, then commits.
            checkpointed(primary)
            commit("a")
            maintenance("node-2").prepare().assert().isEmpty()
            maintenance("node-3").prepare().assert().isEmpty()
        }

    @Test
    fun `a table first claimed while its subscription has a checkpoint is backfilled, never marked at once`(): Unit =
        runBlocking {
            checkpointed(primary)
            // An empty log does not make it marked: the subscription has a checkpoint, so a backfill fills it.
            maintenance("node-1").prepare().map { it.name }.assert().containsExactly(backfill)
            memory.table(type)!!.fill.assert().isEqualTo(SnapshotFill.BACKFILL)
            memory.table(type)!!.backfilled.assert().isFalse()
            // A later node reads the record: it backfills its partitions too.
            maintenance("node-2").prepare().map { it.name }.assert().containsExactly(backfill)
        }

    @Test
    fun `a cluster started on a log that holds entries runs no backfill, the record naming the subscription`(): Unit =
        runBlocking {
            // A log migrate-data imported: entries before any node starts.
            var id = 0
            while ((0 until PARTITIONS).any { log.head(it) == null }) {
                commit("imported-${id++}")
            }
            val first = maintenance("node-1")
            first.prepare().assert().isEmpty()
            memory.table(type)!!.fill.assert().isEqualTo(SnapshotFill.SUBSCRIPTION)
            memory.table(type)!!.backfilled.assert().isFalse()
            // The first node's runtime starts its subscription at the beginning: its starting checkpoints.
            checkpointed(primary)
            maintenance("node-2").prepare().assert().isEmpty()

            // Caught up once the subscription the record names reached every partition's head.
            first.maintain()
            first.maintain()
            memory.table(type)!!.backfilled.assert().isFalse()
            checkpointed(primary) { runBlocking { head(it) } }
            first.maintain()
            memory.table(type)!!.backfilled.assert().isTrue()
        }

    @Test
    fun `a store its subscription is past the beginning of is backfilled by every node until each partition`(): Unit =
        runBlocking {
            var id = 0
            while ((0 until PARTITIONS).any { log.head(it) == null }) {
                commit("a-${id++}")
            }
            checkpointed(primary) { runBlocking { head(it) } }
            val first = maintenance("node-1")
            first.prepare().map { it.name }.assert().containsExactly(backfill)
            // A node joining meanwhile backfills the partitions it runs too.
            maintenance("node-2").prepare().map { it.name }.assert().containsExactly(backfill)

            // The first run records the heads the next ones check against.
            first.maintain()
            checkpoints.save(GROUP, backfill, 0, head(0))
            first.maintain()
            memory.table(type)!!.backfilled.assert().isFalse()
            checkpoints.save(GROUP, backfill, 1, head(1))
            first.maintain()
            memory.table(type)!!.backfilled.assert().isTrue()
        }

    @Test
    fun `a running backfill stops writing once another node marked its table`(): Unit = runBlocking {
        val loads = AtomicInteger()
        val counting = object : SnapshotStore by memory {
            override suspend fun load(ref: AggregateRef): SnapshotRecord? =
                memory.load(ref).also { loads.incrementAndGet() }
        }
        val refs = listOf(commit("a"), commit("b"))
        // Snapshots at the entries' versions: a write that runs loads one and finds nothing to save.
        refs.forEach { memory.save(codec.snapshotOf(model, it, Counter(), 1, Scope.NONE, 0, 0)) }
        memory.claim(type, 1, SnapshotFill.BACKFILL)
        checkpointed(primary)
        val node = maintenance("node-2", counting)
        val running = node.prepare().single()
        suspend fun handle(ref: AggregateRef) {
            running.subscription.handle(SubscribedEntry(log.read(ref, 1, 1).toList().single(), null, emptyList()))
        }

        handle(refs[0])
        loads.get().assert().isEqualTo(1)
        // Another node marks the table, as its own check of the partitions found them caught up.
        memory.mark(type, 1, backfilled = true)
        node.maintain()
        handle(refs[1])
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun `a backfill with an aggregate parked is not marked caught up, and the retry of it writes its snapshot`(): Unit =
        runBlocking {
            // #6755: a parked aggregate counts as done for the checkpoint, its snapshot never written; the mark would end
            // the backfill, and its retry would then resolve the entry and write nothing.
            var id = 0
            val refs = ArrayList<AggregateRef>()
            while ((0 until PARTITIONS).any { log.head(it) == null }) {
                refs += commit("a-${id++}")
            }
            checkpointed(primary) { runBlocking { head(it) } }
            val node = maintenance("node-1")
            val running = node.prepare().single()
            running.name.assert().isEqualTo(backfill)
            node.maintain()
            // The backfill reaches every head, one aggregate parked on the way.
            val parked = refs.first()
            val backfillId = SubscriptionId(CONTEXT, backfill)
            val partition = Partitioner.partitionOf(parked, PARTITIONS)
            ledger.park(ParkedAggregate(backfillId, parked, partition, 1, "IllegalStateException", 0L))
            checkpointed(backfill) { runBlocking { head(it) } }
            node.maintain()
            memory.table(type)!!.backfilled.assert().describedAs("marked with $parked parked").isFalse()
            // Checked again, still parked: still not marked, and told once.
            node.maintain()
            memory.table(type)!!.backfilled.assert().isFalse()
            // The operator's retry runs the handler again, its entry decoded as a retry reads it: the backfill writes.
            val entry = log.readEntries(parked, 1, 1).toList().single()
            val decoded = running.subscription.decoder.decode(entry.stream, entry.position, entry.decodedTransport)
            running.subscription.handle(decoded)
            memory.load(parked).assert().isNotNull()
            ledger.resolve(backfillId, parked, partition)
            node.maintain()
            memory.table(type)!!.backfilled.assert().isTrue()
        }

    private companion object {
        const val CONTEXT = "shop"
        const val GROUP = "service/shop"
        const val PARTITIONS = 2
    }
}
