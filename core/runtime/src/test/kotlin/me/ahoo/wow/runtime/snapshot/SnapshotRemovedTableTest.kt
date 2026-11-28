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
import me.ahoo.wow.spi.snapshot.SnapshotFill
import me.ahoo.wow.spi.snapshot.SnapshotRebuildStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

/**
 * A table whose record says it was removed under it ([SnapshotTable.removed], #7251): the maintenance neither backfills
 * nor rebuilds it, so no revision's rebuild reports it rebuilt, and answers it removed, so its queries are refused.
 */
class SnapshotRemovedTableTest {
    data class Add(val amount: Int)
    data class Added(val amount: Int)

    class Counter {
        var total = 0
    }

    private fun model(revision: Int): AggregateModel<Counter> = aggregate<Counter>(CONTEXT, "counter") {
        stateRevision(revision)
        initial { Counter() }
        decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.amount)) }
        evolve<Added> { total += it.amount }
    }

    private val log = InMemoryEventLog(2)
    private val codec = EventCodec()
    private val kernel = Kernel(log, codec)
    private val type = AggregateType(CONTEXT, "counter")

    /** An in-memory store whose records say removed once [gone] is set, as a store that found its table missing. */
    private class RemovedTables(private val memory: InMemorySnapshotStore = InMemorySnapshotStore()) :
        SnapshotStore by memory,
        SnapshotRebuildStore by memory {
        @Volatile
        var gone = false

        override val rebuilds: SnapshotRebuildStore get() = this

        private fun SnapshotTable.read() = if (gone) SnapshotTable.of(revision, rebuilding, backfilled, fill, true) else this

        override suspend fun table(type: AggregateType): SnapshotTable? = memory.table(type)?.read()

        override suspend fun claim(type: AggregateType, revision: Int, fill: SnapshotFill, lower: Boolean): SnapshotTable =
            memory.claim(type, revision, fill, lower).read()

        override suspend fun mark(
            type: AggregateType,
            revision: Int,
            rebuilding: Boolean?,
            backfilled: Boolean?,
        ): SnapshotTable? = memory.mark(type, revision, rebuilding, backfilled)?.read()
    }

    private fun maintenance(revision: Int, store: SnapshotStore) = SnapshotMaintenance(
        CONTEXT,
        GROUP,
        "node",
        listOf(model(revision)),
        SnapshotTargets.single(store),
        kernel,
        codec,
        log,
        InMemoryLeaseStore(),
        listOf(LogProgress(GROUP, log, InMemoryCheckpointStore(), InMemoryFailureLedger())),
        SnapshotOptions(maintenanceInterval = 1.hours, leaseTtl = 2.hours, memberTtl = 2.hours),
    )

    private suspend fun commit(id: String): AggregateRef {
        val ref = type.ref("tenant", id)
        kernel.execute(model(1), Command("c-$id", "r-$id", ref, Add(1)))
        return ref
    }

    @Test
    fun `a removed table should be neither backfilled nor rebuilt, and answered removed`(): Unit = runBlocking {
        val store = RemovedTables()
        val ref = commit("a")
        maintenance(1, store).prepare()
        store.gone = true
        // A raised revision would rebuild the table; removed, it is left as its record says, never marked rebuilt.
        val raised = maintenance(2, store)
        raised.prepare().assert().isEmpty()
        raised.removed(type).assert().isTrue()
        raised.maintain()
        raised.maintain()
        val table = checkNotNull(store.table(type))
        table.rebuilding.assert().isTrue()
        table.backfilled.assert().isFalse()
        store.load(ref).assert().isNull()
        raised.removed(AggregateType(CONTEXT, "other")).assert().isFalse()
    }

    @Test
    fun `a table not removed should not be answered removed`(): Unit = runBlocking {
        val store = RemovedTables()
        commit("a")
        val maintenance = maintenance(1, store)
        maintenance.removed(type).assert().isFalse()
        maintenance.prepare()
        maintenance.removed(type).assert().isFalse()
    }

    private companion object {
        const val CONTEXT = "shop"
        const val GROUP = "service/shop"
    }
}
