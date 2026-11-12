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
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.ledger.MaintenanceSkips
import me.ahoo.wow.runtime.ledger.SkipLedger
import me.ahoo.wow.runtime.ledger.UnreplayableHistoryException
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.MaintenanceSkipped.Work
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.snapshot.SnapshotRebuildStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.hours

/**
 * A snapshot a rebuild cannot rewrite: one whose history does not replay is parked under `wow.snapshot.rewrite`, the
 * rebuild goes on, and the table is rebuilt once only such snapshots are left; a read or a rewrite a retry may change
 * stops the run; an operator retries or skips the parked one.
 */
class SnapshotRewriteSkipsTest {
    data class Add(val amount: Int)
    data class Added(val amount: Int)

    class Counter {
        var total = 0
    }

    /** The counter at [revision]; [poisoned], an amount of [POISON] no longer replays, as after a broken upgrade. */
    private fun model(revision: Int, poisoned: Boolean = false): AggregateModel<Counter> =
        aggregate<Counter>(CONTEXT, "counter") {
            stateRevision(revision)
            initial { Counter() }
            decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.amount)) }
            evolve<Added> {
                check(!poisoned || it.amount != POISON) { "No amount is $POISON any more." }
                total += it.amount
            }
        }

    private val log = InMemoryEventLog(2)
    private val codec = EventCodec()
    private val kernel = Kernel(log, codec)
    private val leases = InMemoryLeaseStore()
    private val ledger = InMemoryFailureLedger()
    private val type = AggregateType(CONTEXT, "counter")
    private val parked = SubscriptionId(CONTEXT, MaintenanceSkips.SNAPSHOT_REWRITE)
    private val observed = ArrayList<ObservedEvent>()
    private val observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            observed += event
        }
    }

    /** [memory], whose rewrites of the aggregate `b` fail with [failure] while it is set. */
    private class Faulty(val memory: InMemorySnapshotStore = InMemorySnapshotStore()) : SnapshotStore by memory {
        var failure: Exception? = null

        override val rebuilds = object : SnapshotRebuildStore by memory {
            override suspend fun rewrite(snapshot: SnapshotRecord): Boolean {
                failure?.takeIf { snapshot.ref.id == "b" }?.let { throw it }
                return memory.rewrite(snapshot)
            }
        }
    }

    private fun maintenance(
        model: AggregateModel<Counter>,
        store: SnapshotStore,
        skips: Boolean = true,
    ) = SnapshotMaintenance(
        CONTEXT,
        GROUP,
        "node",
        listOf(model),
        SnapshotTargets.single(store),
        kernel,
        codec,
        log,
        leases,
        listOf(LogProgress(GROUP, log, InMemoryCheckpointStore())),
        SnapshotOptions(interval = 1.hours, leaseTtl = 2.hours, memberTtl = 2.hours),
        observer = observer,
        skips = skipsOf(Work.SNAPSHOT_REWRITE).takeIf { skips },
    )

    private fun skipsOf(work: Work) =
        MaintenanceSkips(CONTEXT, work, { SkipLedger(ledger, 2) }, FailureClassification.DEFAULT, observer)

    /** The snapshots of `a`, `b` and `c`, committed and stored at revision 1; `b`'s history holds a [POISON]. */
    private suspend fun stored(store: SnapshotStore): List<AggregateRef> = listOf("a" to 1, "b" to POISON, "c" to 2)
        .map { (id, amount) ->
            val ref = type.ref("tenant", id)
            kernel.execute(model(1), Command("c-$id", "r-$id", ref, Add(amount)))
            store.save(codec.snapshotOf(model(1), ref, Counter(), 1, Scope.NONE, 0, 0))
            ref
        }

    private fun skipped() = observed.filterIsInstance<ObservedEvent.MaintenanceSkipped>()

    @Test
    fun `a snapshot whose history does not replay should be parked, and the table rebuilt once only it is left`(): Unit =
        runBlocking {
            val store = InMemorySnapshotStore()
            val (a, b, c) = stored(store)
            val maintenance = maintenance(model(2, poisoned = true), store)
            maintenance.prepare()
            // Caught up with the log: what keeps the type rebuilding is the table's rebuild alone.
            store.mark(type, 2, backfilled = true)
            maintenance.rebuilding(type).assert().isTrue()

            maintenance.maintain()

            store.load(a)!!.stateRevision.assert().isEqualTo(2)
            store.load(c)!!.stateRevision.assert().isEqualTo(2)
            // Left as stored, still pending: what a query reads of it, as of any snapshot not rewritten yet.
            store.load(b)!!.stateRevision.assert().isEqualTo(1)
            store.pending(type, 2, limit = 10).assert().containsExactly(b)
            store.table(type)!!.rebuilding.assert().isFalse()
            maintenance.rebuilding(type).assert().isFalse()
            ledger.parked(parked, b)!!.reason.assert().startsWith("Its history did not replay").contains("$POISON")
            skipped().map { it.type to it.work }.assert().containsExactly(type to Work.SNAPSHOT_REWRITE)
        }

    @Test
    fun `a rewrite a retry may change should stop the run, parking nothing and leaving the table rebuilding`(): Unit =
        runBlocking {
            val store = Faulty()
            val (_, b) = stored(store)
            val maintenance = maintenance(model(2), store)
            maintenance.prepare()
            store.failure = StorageUnavailableException("The snapshot store is down.")

            assertThrows<StorageUnavailableException> { runBlocking { maintenance.maintain() } }

            store.memory.table(type)!!.rebuilding.assert().isTrue()
            ledger.count(parked, 10).assert().isZero()
            skipped().assert().isEmpty()
            store.failure = null
            maintenance.maintain()
            maintenance.maintain()
            store.memory.load(b)!!.stateRevision.assert().isEqualTo(2)
            store.memory.table(type)!!.rebuilding.assert().isFalse()
        }

    @Test
    fun `an operator's retry should rewrite the snapshot at the current revision once it replays, and resolve it`(): Unit =
        runBlocking {
            val store = InMemorySnapshotStore()
            val (_, b) = stored(store)
            maintenance(model(2, poisoned = true), store).apply { prepare() }.maintain()

            maintenance(model(2, poisoned = true), store).retry(b).assert().isFalse()
            ledger.isParked(parked, b).assert().isTrue()

            // A deployment whose model replays the history again, at the table's revision.
            maintenance(model(2), store).retry(b).assert().isTrue()
            ledger.isParked(parked, b).assert().isFalse()
            store.load(b)!!.stateRevision.assert().isEqualTo(2)
            store.load(b)!!.state.assert().contains("\"total\":$POISON")
            store.pending(type, 2, limit = 10).assert().isEmpty()
        }

    @Test
    fun `a retry on a node of another revision should leave the rewrite to the table's revision, which resolves it`():
        Unit = runBlocking {
        val store = InMemorySnapshotStore()
        val (_, b) = stored(store)
        maintenance(model(2, poisoned = true), store).apply { prepare() }.maintain()

        maintenance(model(1), store).retry(b).assert().isFalse()
        ledger.isParked(parked, b).assert().isTrue()
        store.table(type)!!.rebuilding.assert().isTrue()

        val fixed = maintenance(model(2), store)
        fixed.maintain()
        ledger.isParked(parked, b).assert().isFalse()
        store.load(b)!!.stateRevision.assert().isEqualTo(2)
    }

    @Test
    fun `an operator's skip should resolve the entry, and nothing should take the snapshot for rewritten`(): Unit =
        runBlocking {
            val store = InMemorySnapshotStore()
            val (_, b) = stored(store)
            val maintenance = maintenance(model(2, poisoned = true), store)
            maintenance.prepare()
            maintenance.maintain()

            checkNotNull(maintenance.skips).skip(b).assert().isTrue()

            ledger.isParked(parked, b).assert().isFalse()
            store.load(b)!!.stateRevision.assert().isEqualTo(1)
            store.pending(type, 2, limit = 10).assert().containsExactly(b)
            skipped().assert().hasSize(1)
        }

    @Test
    fun `a snapshot the log holds nothing of should be parked, and the table rebuilt`(): Unit = runBlocking {
        val store = InMemorySnapshotStore()
        val ghost = SnapshotRecord(type.ref("tenant", "ghost"), 1, "f", "{}", firstEventTime = 0, lastEventTime = 0)
        store.save(ghost)
        val maintenance = maintenance(model(2), store)
        maintenance.prepare()

        maintenance.maintain()

        store.load(ghost.ref).assert().isEqualTo(ghost)
        store.table(type)!!.rebuilding.assert().isFalse()
        ledger.parked(parked, ghost.ref)!!.reason.assert().isEqualTo("The log holds no stream of it.")
        skipped().assert().hasSize(1)
    }

    @Test
    fun `a rebuild without skips should stop at a snapshot whose history does not replay, as it did`(): Unit =
        runBlocking {
            val store = InMemorySnapshotStore()
            stored(store)
            val maintenance = maintenance(model(2, poisoned = true), store, skips = false)
            maintenance.prepare()

            assertThrows<UnreplayableHistoryException> { runBlocking { maintenance.maintain() } }

            store.table(type)!!.rebuilding.assert().isTrue()
        }

    private companion object {
        const val CONTEXT = "shop"
        const val GROUP = "service/shop"
        const val POISON = 13
    }
}
