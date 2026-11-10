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
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ArrayNode
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
        events,
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
    fun `a differing value, deleted mark or scope is a difference, and the 9x tags are not compared`() = runTest {
        imported()
        val tampered = changed("order-snapshot.json") { node ->
            (node.get("state") as ObjectNode).put("status", "PAID")
            node.put("deleted", true)
            node.put("ownerId", "customer-2")
            node.put("spaceId", "space-1")
            node.putObject("tags").putArray("region").add("east")
        }
        comparison(listOf(tampered)).compare(order).kinds.keys.assert()
            .containsExactlyInAnyOrder("status", "\$deleted", "\$ownerId", "\$spaceId")
    }

    @Test
    fun `a null array element the V10 state lacks is a difference`() = runTest {
        imported()
        val longer = changed("order-snapshot.json") { node ->
            (node.path("state").path("items") as ArrayNode).addNull()
        }
        val report = comparison(listOf(longer)).compare(order)
        report.passed.assert().isFalse()
        report.kinds.assert().isEqualTo(mapOf("items[]" to 1L))
    }

    @Test
    fun `composed lifecycle events rebuild the scope and deleted mark the 9x snapshot recorded`() = runTest {
        // A 9.x stream of two domain events implementing OwnerTransferred and AggregateDeleted.
        val stream = (WowJson.mapper.readTree(NineXExample.resource("order-stream-v4.json")) as ObjectNode).apply {
            put("id", "0VY3GwcK4OmV00X")
            put("commandId", "0VY3GwcB4OmV00Y")
            put("requestId", "0VY3GwcB4OmV00Y")
            put("version", 5)
            (get("header") as ObjectNode).put("upstream_name", "receipt_order")
            putArray("body").apply {
                addObject().put("id", "e-5a").put("name", "order_handed_over").put("revision", "0.0.1")
                    .putObject("body").put("toOwnerId", "customer-2")
                addObject().put("id", "e-5b").put("name", "order_closed").put("revision", "0.0.1").putObject("body")
            }
        }
        val composed = NineXExample.MAP_TEXT.replace(
            "{ \"from\": \"order_received\", \"to\": \"OrderReceived\" }",
            "{ \"from\": \"order_received\", \"to\": \"OrderReceived\" }, " +
                "{ \"from\": \"order_handed_over\", \"to\": \"OrderReceived\", \"lifecycle\": " +
                "{ \"to\": \"wow.lifecycle.OwnerTransferred\", \"fields\": { \"toOwnerId\": \"ownerId\" } } }, " +
                "{ \"from\": \"order_closed\", \"to\": \"OrderReceived\", \"lifecycle\": " +
                "{ \"to\": \"wow.lifecycle.AggregateDeleted\" } }",
        )
        val plan = planWith(composed)
        val source = InMemoryNineXEventSource(NineXExample.streams() + NineXStream.parse(stream))
        EventStreamImporter(source, plan, { log }, checkpoints).importAll(order)
        val snapshot = changed("order-snapshot.json") { node ->
            node.put("version", 5)
            node.put("ownerId", "customer-2")
            node.put("deleted", true)
            (node.get("state") as ObjectNode).put("status", "RECEIVED")
        }
        val report = StateComparison(
            InMemoryNineXSnapshotSource(listOf(snapshot), source),
            source,
            plan,
            models,
            { log },
            checkpoints,
            report = { printed += it },
        ).compare(order)
        printed.filter { it.startsWith("DIFFERS") }.assert().isEmpty()
        report.passed.assert().isTrue()
        // The imported stream records the scope its written transfer moved to.
        log.read(NineXExample.orderModel.type.ref("(0)", "0VY3GwaK4OmV00E"), 5).toList().single().scope.ownerId
            .assert().isEqualTo("customer-2")
    }

    @Test
    fun `an instance the map excludes is not compared`() = runTest {
        imported()
        val empty = changed("order-snapshot.json") { it.put("aggregateId", "") }
        comparison(NineXExample.snapshots() + empty, NineXExample.planExcluding(RefusedIds.EMPTY)).compare(order).also {
            it.passed.assert().isTrue()
            it.snapshots.assert().isEqualTo(2)
            it.compared.assert().isEqualTo(1)
        }
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

        // Compared again, the instance found differing is compared again, and still differs.
        printed.clear()
        comparison(snapshots).compare(order).also {
            it.changed.assert().isEqualTo(1)
            it.differing.assert().isEqualTo(1)
        }
        printed.count { it.startsWith("DIFFERS") }.assert().isEqualTo(1)

        printed.clear()
        comparison(snapshots, options = CompareOptions(restart = true)).compare(order).differing.assert().isEqualTo(1)
        printed.count { it.startsWith("DIFFERS") }.assert().isEqualTo(1)
    }

    @Test
    fun `compared again, only the instances changed on either side since are compared, and those that differed`() =
        runTest {
            imported()
            val source = InMemoryNineXSnapshotSource(NineXExample.snapshots(), events)
            fun comparison() = StateComparison(source, events, NineXExample.plan, models, { log }, checkpoints, report = {
                printed += it
            })
            comparison().compare(order).passed.assert().isTrue()
            comparison().compare(order).render().assert().isEqualTo(
                "example-service.order compared again: 0 instances changed since the last comparison or differing " +
                    "then, 0 compared; 0 differ in all",
            )
            // 9.x takes a snapshot that differs: compared again, it is found.
            val original = NineXExample.snapshot("order-snapshot.json")
            source.put(
                changed("order-snapshot.json") { node ->
                    (node.get("state") as ObjectNode).put("status", "PAID")
                    node.put("snapshotTime", System.currentTimeMillis())
                },
            )
            comparison().compare(order).also {
                it.changed.assert().isEqualTo(1)
                it.recompared.assert().isEqualTo(1)
                it.differing.assert().isEqualTo(1)
                it.kinds.assert().isEqualTo(mapOf("status" to 1L))
            }
            // The snapshot is put back with its old time: compared again as found differing, it equals.
            source.put(original)
            comparison().compare(order).also {
                it.changed.assert().isEqualTo(1)
                it.passed.assert().isTrue()
            }
            comparison().compare(order).changed.assert().isEqualTo(0)
            // The tail brings in a stream 9.x committed since: the instance is compared again, at its snapshot's
            // version.
            val last = NineXExample.streams().last { it.aggregate == order }
            val late = last.copy(
                id = "late",
                version = last.version + 1,
                commandId = "late",
                requestId = "late",
                createTime = System.currentTimeMillis(),
            )
            // And an instance 9.x has taken no snapshot of yet, which has nothing to compare.
            val fresh = NineXExample.streams().first { it.aggregate == order && it.version == 1 }.let {
                it.copy(
                    id = "fresh",
                    aggregateId = it.aggregateId.copy(id = "fresh"),
                    commandId = "fresh",
                    requestId = "fresh",
                    createTime = System.currentTimeMillis(),
                )
            }
            events.add(late)
            events.add(fresh)
            EventStreamImporter(events, NineXExample.plan, { log }, checkpoints).tailOnce(order)
            comparison().compare(order).also {
                it.changed.assert().isEqualTo(2)
                it.recompared.assert().isEqualTo(1)
                it.passed.assert().isTrue()
            }
            comparison().compare(cart).changed.assert().isNull()
        }

    @Test
    fun `a comparison is compared in full again when what it depends on changed, or it recorded no position`() =
        runTest {
            imported()
            comparison(options = CompareOptions(fingerprint = "a")).compare(order)
            comparison(options = CompareOptions(fingerprint = "b")).compare(order).changed.assert().isNull()
            printed.assert().contains(
                "example-service.order is compared in full again: the map, a Manifest, the classpath (the V10 " +
                    "models), the sample, the time zone or the tool changed since the last comparison.",
            )
            checkpoints.update { it.with(order, it.comparisonOf(order)!!.copy(since = null)) }
            comparison(options = CompareOptions(fingerprint = "b")).compare(order).changed.assert().isNull()
            printed.assert().contains(
                "example-service.order is compared in full again: the last comparison ran with an earlier version, " +
                    "which recorded no position to compare again from.",
            )
            checkpoints.update { it.with(order, it.comparisonOf(order)!!.copy(differs = null)) }
            comparison(options = CompareOptions(fingerprint = "b")).compare(order).changed.assert().isNull()
            printed.assert().contains(
                "example-service.order is compared in full again: more than 1000 instances differed, too many to " +
                    "record.",
            )
            val unindexed = object : NineXEventSource by events, CommitTimeIndexed {
                override fun missingCommitTimeIndexes(aggregates: Collection<NineXAggregate>) =
                    aggregates.associate { it.name + "_event_stream" to "create it" }

                override fun ensureCommitTimeIndex(aggregate: NineXAggregate) = Unit
            }
            StateComparison(
                InMemoryNineXSnapshotSource(NineXExample.snapshots(), events),
                unindexed,
                NineXExample.plan,
                models,
                { log },
                checkpoints,
                CompareOptions(fingerprint = "b"),
                { printed += it },
            ).compare(order).changed.assert().isNull()
            printed.assert().contains(
                "example-service.order is compared in full again: the 9.x store has no commit-time index to read what " +
                    "9.x committed since (the tail creates it).",
            )
        }

    @Test
    fun `compared again, an instance outside the sample is not compared`() = runTest {
        imported()
        val id = NineXExample.snapshot("order-snapshot.json").aggregateId
        val options =
            CompareOptions(sample = (1 until CompareOptions.ALL).first { !CompareOptions(sample = it).sampled(id) })
        val source = InMemoryNineXSnapshotSource(NineXExample.snapshots(), events)
        fun comparison() =
            StateComparison(source, events, NineXExample.plan, models, { log }, checkpoints, options, { printed += it })
        comparison().compare(order).compared.assert().isEqualTo(0)
        source.put(changed("order-snapshot.json") { node -> node.put("snapshotTime", System.currentTimeMillis()) })
        comparison().compare(order).also {
            it.changed.assert().isEqualTo(1)
            it.recompared.assert().isEqualTo(0)
        }
    }

    @Test
    fun `more differing instances than are recorded are compared in full the next time`() = runTest {
        imported()
        // Snapshots of instances the log does not hold: each differs by its version.
        fun missing(n: Int, taken: Long? = null) = changed("order-snapshot.json") { node ->
            node.put("aggregateId", "missing-%04d".format(n))
            taken?.let { node.put("snapshotTime", it) }
        }
        val source = InMemoryNineXSnapshotSource((1..StateComparison.MAX_RECORDED).map { missing(it) }, events)
        fun comparison() = StateComparison(
            source,
            events,
            NineXExample.plan,
            models,
            { log },
            checkpoints,
            CompareOptions(pageSize = 100),
            { printed += it },
        )
        comparison().compare(
            order
        ).kinds.assert().isEqualTo(mapOf("\$version" to StateComparison.MAX_RECORDED.toLong()))
        checkpoints.load().comparisonOf(order)!!.differs!!.size.assert().isEqualTo(StateComparison.MAX_RECORDED)
        // One more, taken since: compared again, the instances that differ are too many to record.
        source.add(missing(StateComparison.MAX_RECORDED + 1, System.currentTimeMillis()))
        comparison().compare(order).also {
            it.changed.assert().isEqualTo(StateComparison.MAX_RECORDED + 1L)
            it.differing.assert().isEqualTo(StateComparison.MAX_RECORDED + 1L)
        }
        checkpoints.load().comparisonOf(order)!!.differs.assert().isNull()
        comparison().compare(order).also {
            it.changed.assert().isNull()
            it.differing.assert().isEqualTo(StateComparison.MAX_RECORDED + 1L)
        }
        printed.assert().contains(
            "example-service.order is compared in full again: more than 1000 instances differed, too many to record.",
        )
        checkpoints.load().comparisonOf(order)!!.differs.assert().isNull()
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
            events,
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
        assertThrows<IllegalArgumentException> { CompareOptions(lookbackMillis = -1) }
    }
}
