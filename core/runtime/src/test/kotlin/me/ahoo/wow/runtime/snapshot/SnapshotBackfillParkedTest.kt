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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

/**
 * Deep review r1 (runtime snapshot): a backfill is checked caught up by its checkpoints alone
 * (`SnapshotBackfills.check`: each partition's checkpoint at the head recorded at the previous run). An aggregate the
 * backfill parked counts as done for the checkpoint, so the table is marked backfilled while its snapshot was never
 * written. From then on the backfill is inactive (`SnapshotSubscription.write`: `!active(type)` passes), so an
 * operator's retry of the parked aggregate runs the handler, succeeds, and resolves the ledger entry with no snapshot
 * written: the aggregate is missing from the target (a query copy, say) until it commits again.
 */
class SnapshotBackfillParkedTest {
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
    private val checkpoints = InMemoryCheckpointStore()
    private val memory = InMemorySnapshotStore()
    private val type = AggregateType(CONTEXT, "counter")
    private val primary = SnapshotTargets.single(memory).subscriptionOf(SnapshotTargets.DEFAULT_STORE)
    private val backfill = "$primary#counter"

    private fun maintenance(node: String) = SnapshotMaintenance(
        CONTEXT,
        GROUP,
        node,
        listOf(model),
        SnapshotTargets.single(memory),
        kernel,
        codec,
        log,
        InMemoryLeaseStore(),
        listOf(LogProgress(GROUP, log, checkpoints)),
        SnapshotOptions(interval = 1.hours, leaseTtl = 2.hours, memberTtl = 2.hours),
    )

    private suspend fun commit(id: String): AggregateRef {
        val ref = type.ref("tenant", id)
        kernel.execute(model, Command("c-$id", "r-$id", ref, Add(1)))
        return ref
    }

    @Test
    fun `a retry of an aggregate the backfill parked should write its snapshot, the table marked or not`(): Unit =
        runBlocking {
            var id = 0
            val refs = ArrayList<AggregateRef>()
            while ((0 until PARTITIONS).any { log.head(it) == null }) {
                refs += commit("a-${id++}")
            }
            // The store's subscription is past the beginning: a backfill fills the table.
            (0 until PARTITIONS).forEach { checkpoints.save(GROUP, primary, it, checkNotNull(log.head(it))) }
            val node = maintenance("node-1")
            val running = node.prepare().single()
            running.name.assert().isEqualTo(backfill)
            node.maintain()
            // The backfill reaches every head; one aggregate's handler failed and parked it, which the checkpoint
            // passes as done. Nothing of it was written.
            val parked = refs.first()
            (0 until PARTITIONS).forEach { checkpoints.save(GROUP, backfill, it, checkNotNull(log.head(it))) }
            node.maintain()
            memory.table(type)!!.backfilled.assert().describedAs("marked with $parked parked").isTrue()
            memory.load(parked).assert().isNull()
            // An operator retries the parked aggregate: its handler runs again, and succeeds.
            running.subscription.handle(SubscribedEntry(log.read(parked, 1, 1).toList().single(), null, emptyList()))
            memory.load(parked).assert()
                .describedAs("$parked's snapshot after the retry the ledger now counts as resolved")
                .isNotNull()
        }

    private companion object {
        const val CONTEXT = "shop"
        const val GROUP = "service/shop"
        const val PARTITIONS = 2
    }
}
