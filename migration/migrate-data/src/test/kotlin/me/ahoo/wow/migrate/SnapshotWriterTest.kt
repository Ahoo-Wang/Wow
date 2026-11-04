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

package me.ahoo.wow.migrate

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SnapshotWriterTest {
    private val order = NineXAggregate("example-service", "order")
    private val cart = NineXAggregate("example-service", "cart")
    private val streams = NineXExample.streams()
    private val events = InMemoryNineXEventSource(streams)
    private val log = InMemoryEventLog(4)
    private val store = InMemorySnapshotStore()
    private val checkpoints = InMemoryCheckpointRepository()
    private val reports = mutableListOf<SnapshotProgress>()
    private val printed = mutableListOf<String>()
    private val models = MigrationModels(listOf(NineXExample.orderModel, NineXExample.cartModel))

    private suspend fun imported(source: NineXEventSource = events, plan: MigrationPlan = NineXExample.plan) {
        val importer = EventStreamImporter(source, plan, { log }, checkpoints)
        importer.importAll(order)
        importer.importAll(cart)
    }

    private fun writer(
        source: NineXEventSource = events,
        options: ImportOptions = ImportOptions(),
        plan: MigrationPlan = NineXExample.plan,
    ) = SnapshotWriter(
        source,
        plan,
        models,
        { log },
        { store },
        checkpoints,
        options,
        { reports += it },
        report = { printed += it },
    )

    @Test
    fun `the snapshot of each imported instance holds its V10 state at the last version`() = runTest {
        imported()
        writer(options = ImportOptions(pageSize = 1)).also {
            it.writeAll(order).written.assert().isEqualTo(1)
            it.writeAll(cart).written.assert().isEqualTo(1)
        }
        val id = streams.first { it.aggregate == order }.aggregateId
        val snapshot = store.load(NineXExample.orderModel.type.ref(id.tenantId, id.id))!!
        snapshot.version.assert().isEqualTo(4)
        snapshot.stateRevision.assert().isEqualTo(NineXExample.orderModel.stateRevision)
        snapshot.schemaFingerprint.assert().isEqualTo(EventCodec().stateSchemaFingerprint(NineXExample.orderModel))
        snapshot.scope.ownerId.assert().isEqualTo("customer-1")
        snapshot.firstEventTime.assert().isEqualTo(1792004186903)
        snapshot.lastEventTime.assert().isEqualTo(1792004186976)
        snapshot.firstOperator.assert().isEqualTo(Operator("user-1", "(0)"))
        snapshot.operator.assert().isNull()
        NineXExample.sameJson(
            WowJson.mapper.readTree(snapshot.state),
            WowJson.mapper.readTree(NineXExample.resource("order-state.json")),
        ).assert().isTrue()
        reports.last().render().assert()
            .isEqualTo("example-service.cart snapshots done: 1 instances, 1 V10 snapshots written, 0 already there")
        checkpoints.load().snapshotsOf(cart)!!.completed.assert().isTrue()
    }

    @Test
    fun `writing again catches up on nothing, and a restart finds every snapshot there`() = runTest {
        imported()
        writer().writeAll(order)
        writer().writeAll(order).also {
            it.caughtUp.assert().isTrue()
            it.render().assert().isEqualTo(
                "example-service.order snapshots caught up: 0 instances the V10 log changed since, 0 V10 snapshots " +
                    "written, 0 already there",
            )
        }
        writer(options = ImportOptions(restart = true)).writeAll(order).also {
            it.caughtUp.assert().isFalse()
            it.written.assert().isEqualTo(0)
            it.skipped.assert().isEqualTo(1)
        }
    }

    @Test
    fun `once a walk has completed, a run writes the snapshots of the instances the log changed since`() = runTest {
        imported()
        writer().also {
            it.writeAll(order)
            it.writeAll(cart)
        }
        val walked = checkpoints.load().snapshotsOf(order)!!.heads!!
        walked.assert().isNotEmpty()
        // 9.x commits once more, and the tail brings it in.
        val last = streams.last { it.aggregate == order }
        val late = last.copy(
            id = "late",
            version = last.version + 1,
            commandId = "late",
            requestId = "late",
            createTime = System.currentTimeMillis(),
        )
        val source = InMemoryNineXEventSource(streams + late)
        EventStreamImporter(source, NineXExample.plan, { log }, checkpoints).tailOnce(order)
        writer(source).writeAll(order).also {
            it.caughtUp.assert().isTrue()
            it.written.assert().isEqualTo(1)
            it.done.assert().isTrue()
        }
        store.load(NineXExample.orderModel.type.ref(late.aggregateId.tenantId, late.aggregateId.id))!!.version
            .assert().isEqualTo(late.version)
        // The cart's log did not change.
        writer(source).writeAll(cart).written.assert().isEqualTo(0)
        checkpoints.load().snapshotsOf(order)!!.also {
            it.heads.assert().isNotEqualTo(walked)
            it.written.assert().isEqualTo(2)
        }
        writer(source).writeAll(order).written.assert().isEqualTo(0)
    }

    @Test
    fun `a catch-up whose instance does not rebuild reads the same entries again on the next run`() = runTest {
        imported()
        writer().writeAll(cart)
        val walked = checkpoints.load().snapshotsOf(cart)!!.heads
        // A cart the log holds since, whose last event the V10 model does not read.
        val unreadable = InMemoryNineXEventSource(streams + NineXExample.cartRemoving("cart-null", "[null]"))
        EventStreamImporter(unreadable, NineXExample.plan, { log }, checkpoints, ImportOptions(restart = true))
            .importAll(cart)
        repeat(2) {
            writer().writeAll(cart).also {
                it.caughtUp.assert().isTrue()
                it.failed.assert().isEqualTo(1)
            }
            checkpoints.load().snapshotsOf(cart)!!.heads.assert().isEqualTo(walked)
        }
        printed.last().assert().startsWith("FAILED example.cart/(0)/cart-null, the 9.x instance ")
    }

    @Test
    fun `a walk an earlier version completed, recording no heads, is walked again`() = runTest {
        imported()
        writer().writeAll(order)
        checkpoints.update { it.with(order, it.snapshotsOf(order)!!.copy(heads = null)) }
        writer().writeAll(order).also {
            it.caughtUp.assert().isFalse()
            it.skipped.assert().isEqualTo(1)
            it.done.assert().isTrue()
        }
        printed.assert().contains(
            "example-service.order: its snapshots were written by an earlier version, which recorded no position of " +
                "the V10 log to catch up from, so every instance is walked again.",
        )
        checkpoints.load().snapshotsOf(order)!!.heads.assert().isNotNull()
    }

    @Test
    fun `an instance the map excludes has no snapshot`() = runTest {
        val empty = streams.first { it.aggregate == order && it.version == 1 }.let {
            it.copy(id = "empty", aggregateId = it.aggregateId.copy(id = ""))
        }
        val source = InMemoryNineXEventSource(streams + empty)
        val excluding = NineXExample.planExcluding(RefusedIds.EMPTY)
        imported(source, excluding)
        writer(source, plan = excluding).writeAll(order).written.assert().isEqualTo(1)
    }

    @Test
    fun `snapshots need the full import, and every instance whose snapshot is not written is reported`() = runTest {
        assertThrows<MigrationException> { writer().writeAll(order) }.message!!.assert()
            .contains("run the import before writing snapshots")
        // A cart the import copied as 9.x stored it, whose last event the V10 model does not read.
        val unreadable = NineXExample.cartRemoving("cart-null", "[null]")
        imported(InMemoryNineXEventSource(streams + unreadable))
        val missing = (1..2).map { n ->
            streams.first { it.aggregate == order && it.version == 1 }
                .let { it.copy(id = "later-$n", aggregateId = it.aggregateId.copy(id = "later-$n")) }
        }
        val ahead = InMemoryNineXEventSource(streams + unreadable + missing)
        writer(ahead, ImportOptions(pageSize = 1)).also { writer ->
            writer.writeAll(order).also {
                it.written.assert().isEqualTo(1)
                it.failed.assert().isEqualTo(2)
                it.done.assert().isFalse()
                it.render().assert()
                    .isEqualTo(
                        "example-service.order snapshots: 3 instances, 1 V10 snapshots written, 0 already there, 2 failed"
                    )
            }
            writer.writeAll(cart).failed.assert().isEqualTo(1)
        }
        printed.assert().contains(
            "FAILED example.order/(0)/later-1, the 9.x instance example-service.order/(0)/later-1 has no complete " +
                "history in the V10 log: 9.x wrote it after the import, so run the tail (tail --once), then snapshot " +
                "again.",
            "FAILED example.order/(0)/later-2, the 9.x instance example-service.order/(0)/later-2 has no complete " +
                "history in the V10 log: 9.x wrote it after the import, so run the tail (tail --once), then snapshot " +
                "again.",
        )
        printed.last().assert().startsWith(
            "FAILED example.cart/(0)/cart-null, the 9.x instance example-service.cart/(0)/cart-null: " +
                "example.cart/(0)/cart-null v4: the event example.cart.CartItemRemoved@1 does not read: ",
        ).contains("productIds")
        // The walk starts again from the first instance, and passes over the snapshot it wrote.
        checkpoints.load().snapshotsOf(order).assert().isEqualTo(SnapshotCheckpoint())
        writer(events).writeAll(order).also {
            it.skipped.assert().isEqualTo(1)
            it.done.assert().isTrue()
        }
    }
}
