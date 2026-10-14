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
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemorySnapshotStore
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
    private val models = MigrationModels(listOf(NineXExample.orderModel, NineXExample.cartModel))

    private suspend fun imported() {
        val importer = EventStreamImporter(events, NineXExample.plan, { log }, checkpoints)
        importer.importAll(order)
        importer.importAll(cart)
    }

    private fun writer(source: NineXEventSource = events, options: ImportOptions = ImportOptions()) = SnapshotWriter(
        source,
        NineXExample.plan,
        models,
        { log },
        { store },
        checkpoints,
        options,
        { reports += it },
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
    fun `writing again skips what is written, and a restart finds every snapshot there`() = runTest {
        imported()
        writer().writeAll(order)
        writer().writeAll(order).written.assert().isEqualTo(1)
        writer(options = ImportOptions(restart = true)).writeAll(order).also {
            it.written.assert().isEqualTo(0)
            it.skipped.assert().isEqualTo(1)
        }
    }

    @Test
    fun `snapshots need the full import, and an instance missing from the log stops them`() = runTest {
        assertThrows<MigrationException> { writer().writeAll(order) }.message!!.assert()
            .contains("run the import before writing snapshots")
        imported()
        val later = streams.first { it.aggregate == order && it.version == 1 }.let {
            it.copy(id = "later", aggregateId = it.aggregateId.copy(id = "later"))
        }
        val ahead = InMemoryNineXEventSource(streams + later)
        assertThrows<MigrationException> { writer(ahead).writeAll(order) }.message!!.assert()
            .contains("has no complete history in the V10 log")
    }
}
