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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.runtime.access.AccessSchema
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventLog
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
    fun `each instance's history is stamped with its state's attributes as of its last version, a later stamp kept`() =
        runTest {
            imported()
            val id = streams.first { it.aggregate == order }.aggregateId
            val ref = NineXExample.orderModel.type.ref(id.tenantId, id.id)
            // A node stamped the first stream as of a later version.
            val later = AccessValues.ofStream(emptyMap(), Scope("someone"))
            log.stamp(ref, later, asOf = 9, from = 1, until = 2).assert().isEqualTo(1)
            writer().writeAll(order)
            val snapshot = store.load(ref)!!
            val current = AccessValues.ofStream(emptyMap(), snapshot.scope)
            log.read(ref).toList().map { it.access to it.accessVersion }.assert().containsExactly(
                later to 9,
                current to 4,
                current to 4,
                current to 4,
            )
            // A catch-up stamps the instances the log changed since, as of their new last version.
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
            writer(source).writeAll(order).caughtUp.assert().isTrue()
            log.read(ref).toList().map { it.accessVersion }.assert().containsExactly(9, 5, 5, 5, 5)
        }

    @Test
    fun `a type whose every 9x aggregate's snapshots completed without a failure has its walk recorded`() = runTest {
        imported()
        val orderType = NineXExample.orderModel.type
        val cartType = NineXExample.cartModel.type
        val writer = writer()
        val done = listOf(writer.writeAll(order), writer.writeAll(cart))
        // A cart that failed, or is missing, records nothing of its type.
        writer.recordWalks(listOf(done[0], done[1].copy(failed = 1)))
        log.walk(orderType).assert().isEqualTo(
            AccessWalk(AccessSchema.of(NineXExample.orderModel), emptyMap(), walked = 1, completed = true),
        )
        log.walk(cartType).assert().isNull()
        writer.recordWalks(listOf(done[0]))
        log.walk(cartType).assert().isNull()
        printed.assert().containsExactly(
            "$orderType: its access walk is recorded as completed for the schema []: 1 instances stamped.",
        )
        // A walk recorded is left as it is, and one of another schema is reported.
        log.claim(cartType, AccessWalk("region:string", emptyMap()), expected = null).assert().isTrue()
        writer.recordWalks(done)
        log.walk(cartType)!!.schema.assert().isEqualTo("region:string")
        printed.assert().hasSize(2)
        printed.last().assert().isEqualTo(
            "$cartType: the log records its access walk for the schema [region:string], not this model's []: it " +
                "is left as it is, and the V10 nodes of this model walk the type again.",
        )
    }

    @Test
    fun `a log that keeps no attributes is not stamped, and one that holds nothing of a type or records no claim has no walk`() =
        runTest {
            imported()
            // A log of an earlier backend, without stamps or walks: the snapshots alone.
            val plain = object : EventLog by log {}
            val writer = SnapshotWriter(events, NineXExample.plan, models, { plain }, { store }, checkpoints)
            val done = listOf(writer.writeAll(order), writer.writeAll(cart))
            done.map { it.written }.assert().containsExactly(1, 1)
            writer.recordWalks(done)
            log.walk(NineXExample.orderModel.type).assert().isNull()
            // A log that holds none of the types: nothing to record.
            val empty = InMemoryEventLog(4)
            SnapshotWriter(events, NineXExample.plan, models, { empty }, { store }, checkpoints).recordWalks(done)
            empty.walk(NineXExample.orderModel.type).assert().isNull()
            // A claim the log does not record, and no walk recorded either: nothing to report.
            val refusing = object : EventLog by log, AccessWalks by log {
                override suspend fun claim(type: AggregateType, walk: AccessWalk, expected: String?) = false
            }
            SnapshotWriter(
                events,
                NineXExample.plan,
                models,
                { refusing },
                { store },
                checkpoints,
                report = { printed += it }
            )
                .recordWalks(done)
            printed.assert().isEmpty()
        }

    @Test
    fun `a type two 9x aggregates are mapped to has its walk recorded once both completed`() = runTest {
        imported()
        val done = writer().let { listOf(it.writeAll(order), it.writeAll(cart)) }
        // The 9.x cart mapped to the V10 order too.
        val merged = NineXExample.map.let { map ->
            map.copy(
                aggregates = map.aggregates.map { if (it.to == "example.cart") it.copy(to = "example.order") else it }
            )
        }.let { MigrationPlan(it, NineXExample.manifest) }
        val orderType = NineXExample.orderModel.type
        writer(plan = merged).recordWalks(listOf(done[0]))
        log.walk(orderType).assert().isNull()
        writer(plan = merged).recordWalks(done)
        log.walk(orderType)!!.walked.assert().isEqualTo(2)
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
