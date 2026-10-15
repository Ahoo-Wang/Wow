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
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode

class StateComparisonTest {
    private val order = NineXAggregate("example-service", "order")
    private val cart = NineXAggregate("example-service", "cart")
    private val events = InMemoryNineXEventSource(NineXExample.streams())
    private val log = InMemoryEventLog(4)
    private val checkpoints = InMemoryCheckpointRepository()
    private val printed = mutableListOf<String>()
    private val models = MigrationModels(listOf(NineXExample.orderModel, NineXExample.cartModel))

    private suspend fun imported() {
        val importer = EventStreamImporter(events, NineXExample.plan, { log }, checkpoints)
        importer.importAll(order)
        importer.importAll(cart)
    }

    private fun comparison(
        snapshots: Collection<NineXSnapshot> = NineXExample.snapshots(),
        plan: MigrationPlan = NineXExample.plan,
        options: CompareOptions = CompareOptions(),
    ) = StateComparison(
        InMemoryNineXSnapshotSource(snapshots, events),
        plan,
        models,
        { log },
        checkpoints,
        options,
        { printed += it },
    )

    private fun planWith(map: String) = MigrationPlan(MigrationMap.read(map), NineXExample.manifest)

    private fun changed(name: String, change: (ObjectNode) -> Unit): NineXSnapshot {
        val node = WowJson.mapper.readTree(NineXExample.resource(name)) as ObjectNode
        change(node)
        return NineXSnapshot.parse(node)
    }

    @Test
    fun `the 9x example's states equal the V10 states rebuilt from the imported log`() = runTest {
        imported()
        val comparison = comparison()
        val orderReport = comparison.compare(order)
        orderReport.passed.assert().isTrue()
        orderReport.done.assert().isTrue()
        orderReport.instances.assert().isEqualTo(1)
        orderReport.snapshots.assert().isEqualTo(1)
        orderReport.compared.assert().isEqualTo(1)
        comparison.compare(cart).passed.assert().isTrue()
        printed.last().assert()
            .isEqualTo(
                "example-service.cart compared: 1 of 1 instances have a 9.x snapshot (100.0%), 1 compared, 0 differ"
            )
        printed.none { it.startsWith("DIFFERS") }.assert().isTrue()
    }

    @Test
    fun `a broken state mapping is reported with the instance, its version and the fields`() = runTest {
        imported()
        val broken = NineXExample.MAP_TEXT.replace(
            "\"to\": \"example.order\",",
            "\"to\": \"example.order\", \"state\": { \"fields\": { \"paidAmount\": \"paid\" } },",
        )
        val report = comparison(plan = planWith(broken)).compare(order)
        report.passed.assert().isFalse()
        report.differing.assert().isEqualTo(1)
        report.kinds.assert().isEqualTo(mapOf("paid" to 1L, "paidAmount" to 1L))
        printed.first { it.startsWith("DIFFERS") }.assert()
            .isEqualTo(
                "DIFFERS example-service.order (0)/0VY3GwaK4OmV00E v4: paid: 9.x 25.00, V10 (none); " +
                    "paidAmount: 9.x (none), V10 25.00",
            )
        report.render().assert().endsWith("1 compared, 1 differ; differing fields: paid (1), paidAmount (1)")
    }

    @Test
    fun `a differing value, deleted mark, scope or tag is a difference`() = runTest {
        imported()
        val tampered = changed("order-snapshot.json") { node ->
            (node.get("state") as ObjectNode).put("status", "PAID")
            node.put("deleted", true)
            node.put("ownerId", "customer-2")
            node.put("spaceId", "space-1")
            node.putObject("tags").putArray("region").add("east")
        }
        comparison(listOf(tampered)).compare(order).kinds.keys.assert()
            .containsExactlyInAnyOrder("status", "\$deleted", "\$ownerId", "\$spaceId", "\$tags")
    }

    @Test
    fun `a version the V10 log does not hold is a difference`() = runTest {
        imported()
        val ahead = changed("order-snapshot.json") { it.put("version", 5) }
        comparison(listOf(ahead)).compare(order).kinds.assert().isEqualTo(mapOf("\$version" to 1L))
        printed.first { it.startsWith("DIFFERS") }.assert().contains("v5: \$version: 9.x 5, V10 4")
        val missing = changed("order-snapshot.json") { it.put("_id", "elsewhere") }
        comparison(listOf(missing), options = CompareOptions(restart = true)).compare(order).kinds.assert()
            .isEqualTo(mapOf("\$version" to 1L))
    }

    @Test
    fun `a comparison resumes from its checkpoint, keeps what it found, and restarts when told`() = runTest {
        imported()
        val second = changed("order-snapshot.json") { node ->
            node.put("aggregateId", "0VY3GwaK4OmV00Z")
            (node.get("state") as ObjectNode).put("id", "0VY3GwaK4OmV00Z")
        }
        val snapshots = listOf(NineXExample.snapshot("order-snapshot.json"), second)
        comparison(snapshots, options = CompareOptions(pageSize = 1)).compare(order).also {
            it.snapshots.assert().isEqualTo(2)
            it.differing.assert().isEqualTo(1)
        }
        printed.assert().anyMatch { it.startsWith("example-service.order: 1 of 1 instances") }
        checkpoints.load().comparisonOf(order)!!.completed.assert().isTrue()

        printed.clear()
        comparison(snapshots).compare(order).differing.assert().isEqualTo(1)
        printed.none { it.startsWith("DIFFERS") }.assert().isTrue()

        comparison(snapshots, options = CompareOptions(restart = true)).compare(order).differing.assert().isEqualTo(1)
        printed.count { it.startsWith("DIFFERS") }.assert().isEqualTo(1)
    }

    @Test
    fun `a sample compares the same share of the instances on every run`() = runTest {
        imported()
        val options = CompareOptions(sample = 1)
        val sampled = options.sampled(NineXExample.snapshot("order-snapshot.json").aggregateId)
        comparison(options = options).compare(order).compared.assert().isEqualTo(if (sampled) 1 else 0)
        (0 until 1000).count { options.sampled(NineXAggregateId("id-$it", "(0)")) }.assert().isBetween(1, 30)
        CompareOptions().sampled(NineXAggregateId("any", "(0)")).assert().isTrue()
        // Another sample starts the comparison over.
        comparison(options = CompareOptions(sample = 100)).compare(order).compared.assert().isEqualTo(1)
    }

    @Test
    fun `a comparison needs the full import and the V10 model`() = runTest {
        assertThrows<MigrationException> { comparison().compare(order) }.message!!.assert()
            .contains("run the import before compare")
        imported()
        val without = StateComparison(
            InMemoryNineXSnapshotSource(),
            NineXExample.plan,
            MigrationModels(emptyList()),
            { log },
            checkpoints,
        )
        assertThrows<MigrationException> { without.compare(order) }.message!!.assert()
            .contains("No V10 model of example.order")
    }

    @Test
    fun `compare options are bounded`() {
        assertThrows<IllegalArgumentException> { CompareOptions(pageSize = 0) }
        assertThrows<IllegalArgumentException> { CompareOptions(concurrency = 0) }
        assertThrows<IllegalArgumentException> { CompareOptions(sample = 0) }
        assertThrows<IllegalArgumentException> { CompareOptions(sample = 101) }
    }
}
