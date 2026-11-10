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
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.node.ObjectNode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

/**
 * A comparison stopped half-way: it resumes where it stopped, keeping what it found, only on the sample and the inputs
 * it started with; otherwise it starts again from the first instance and says why.
 */
class ComparisonResumeTest {
    private val order = NineXAggregate("example-service", "order")
    private val events = InMemoryNineXEventSource(NineXExample.streams())
    private val log = InMemoryEventLog(4)
    private val checkpoints = InMemoryCheckpointRepository()
    private val models = MigrationModels(listOf(NineXExample.orderModel))
    private val printed = mutableListOf<String>()

    /** Where each page a comparison read started: null at the first instance. */
    private val reads = mutableListOf<NineXAggregateId?>()

    // Both differ: the first at a version the log does not hold, the second an instance it does not hold.
    private val first = changed { node -> node.put("version", 99) }
    private val second = changed { node ->
        node.put("aggregateId", "0VY3GwaK4OmV00Z")
        (node.get("state") as ObjectNode).put("id", "0VY3GwaK4OmV00Z")
    }

    private fun changed(change: (ObjectNode) -> Unit): NineXSnapshot {
        val node = WowJson.mapper.readTree(NineXExample.resource("order-snapshot.json")) as ObjectNode
        change(node)
        return NineXSnapshot.parse(node)
    }

    private suspend fun imported() {
        EventStreamImporter(events, NineXExample.plan, { log }, checkpoints).importAll(order)
    }

    /** [source] as a comparison reads it, each page's start recorded in [reads]. */
    private fun recorded(source: NineXSnapshotSource) = object : NineXSnapshotSource by source {
        override fun snapshots(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int): List<NineXSnapshot> {
            reads += after
            return source.snapshots(aggregate, after, limit)
        }
    }

    /** [source] whose snapshot connection is lost after its first page, as a comparison is stopped half-way. */
    private fun interruptedAfterFirstPage(source: NineXSnapshotSource) = object : NineXSnapshotSource by source {
        override fun snapshots(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int): List<NineXSnapshot> {
            if (after != null) throw IOException("the snapshot connection was lost after the first page")
            return source.snapshots(aggregate, after, limit)
        }
    }

    private suspend fun compare(
        options: CompareOptions,
        source: NineXSnapshotSource = InMemoryNineXSnapshotSource(listOf(first, second), events),
        plan: MigrationPlan = NineXExample.plan,
    ): ComparisonReport =
        StateComparison(source, events, plan, models, { log }, checkpoints, options, { printed += it }).compare(order)

    /** A comparison of [first] and [second] on [options], stopped after the first page. */
    private suspend fun stopped(options: CompareOptions) {
        val source = interruptedAfterFirstPage(InMemoryNineXSnapshotSource(listOf(first, second), events))
        runCatching { compare(options, source) }.exceptionOrNull().assert().isInstanceOf(IOException::class.java)
        checkpoints.load().comparisonOf(order)!!.also {
            it.completed.assert().isFalse()
            it.differing.assert().isEqualTo(1)
        }
        printed.clear()
    }

    /** A comparison on [options] of [first] and [second], from where it reads its first page. */
    private suspend fun resumed(options: CompareOptions): ComparisonReport =
        compare(options, recorded(InMemoryNineXSnapshotSource(listOf(first, second), events)))

    private fun startedAgainBecause(reason: String) {
        printed.assert().contains("example-service.order is compared in full again from the first instance: $reason.")
        reads.first().assert().isNull()
        // Both instances compared in this run, the first again.
        printed.count { it.startsWith("DIFFERS") }.assert().isEqualTo(2)
    }

    @Test
    fun `a comparison stopped half-way on the same inputs resumes where it stopped, with what it found`() = runTest {
        imported()
        val options = CompareOptions(pageSize = 1, fingerprint = "a")
        stopped(options)
        resumed(options).also {
            it.snapshots.assert().isEqualTo(2)
            it.differing.assert().isEqualTo(2)
            it.kinds.assert().isEqualTo(mapOf("\$version" to 2L))
        }
        reads.first().assert().isEqualTo(first.aggregateId)
        printed.assert().noneMatch { it.contains("compared in full again") }
        // The first difference was kept, not found again.
        printed.count { it.startsWith("DIFFERS") }.assert().isEqualTo(1)
    }

    @Test
    fun `a comparison stopped half-way on other inputs starts again from the first instance`() = runTest {
        imported()
        stopped(CompareOptions(pageSize = 1, fingerprint = "a"))
        resumed(CompareOptions(pageSize = 1, fingerprint = "b")).differing.assert().isEqualTo(2)
        startedAgainBecause(
            "the map, a Manifest, the classpath (the V10 models), the sample, the time zone or the tool changed " +
                "since it stopped",
        )
    }

    @Test
    fun `a comparison an earlier version stopped half-way starts again from the first instance`() = runTest {
        imported()
        // An earlier version compared the first snapshot, recording neither its inputs nor the instances that differ.
        checkpoints.update {
            it.with(order, CompareCheckpoint(after = first.aggregateId, snapshots = 1, compared = 1, differing = 1))
        }
        resumed(CompareOptions(pageSize = 1, fingerprint = "a")).also {
            it.snapshots.assert().isEqualTo(2)
            it.differing.assert().isEqualTo(2)
        }
        startedAgainBecause("it was stopped by an earlier version, which recorded no inputs")
    }

    @Test
    fun `a comparison on another sample starts again from the first instance, stopped or completed`() = runTest {
        imported()
        stopped(CompareOptions(pageSize = 1, fingerprint = "a"))
        resumed(CompareOptions(pageSize = 1, fingerprint = "a", sample = 50))
        printed.assert().contains(
            "example-service.order is compared in full again from the first instance: the sample changed from 100% " +
                "to 50%.",
        )
        reads.first().assert().isNull()
        // A completed comparison of another sample is not compared again from it either.
        printed.clear()
        reads.clear()
        resumed(CompareOptions(pageSize = 1, fingerprint = "a")).changed.assert().isNull()
        printed.assert().contains(
            "example-service.order is compared in full again from the first instance: the sample changed from 50% " +
                "to 100%.",
        )
        reads.first().assert().isNull()
    }

    @Test
    fun `a comparison stopped half-way on another map starts again from the first instance`(
        @TempDir directory: Path,
    ) = runTest {
        imported()
        // A field only the 9.x snapshot has, which the first map leaves out of the comparison.
        val snapshot = NineXExample.snapshot("order-snapshot.json").let { snapshot ->
            snapshot.copy(state = (snapshot.state as ObjectNode).deepCopy().put("legacyOnly", "to review"))
        }
        val source = InMemoryNineXSnapshotSource(listOf(snapshot), events)
        val ignoring = NineXExample.map.copy(
            aggregates = NineXExample.map.aggregates.map { aggregate ->
                if (aggregate.source() != order) {
                    aggregate
                } else {
                    aggregate.copy(state = aggregate.state.copy(ignore = aggregate.state.ignore + "legacyOnly"))
                }
            },
        )
        val mapFile = directory.resolve("migration-map.json")
        val config = MigrationConfig(
            map = mapFile.toString(),
            source = SourceConfig("mongodb://unused", "nine_x"),
            target = TargetConfig(type = "postgres", jdbcUrl = "jdbc:postgresql://unused/v10"),
        )

        // The fingerprint the CLI gives the comparison, of the map file as it is.
        fun options(restart: Boolean = false) = CompareOptions(
            pageSize = 1,
            fingerprint = ComparisonFingerprint.of(config, CompareOptions.ALL, ZoneId.of("UTC"), tool = null),
            restart = restart,
        )

        Files.writeString(mapFile, WowJson.mapper.writeValueAsString(ignoring))
        runCatching {
            StateComparison(
                interruptedAfterFirstPage(source),
                events,
                MigrationPlan(ignoring, NineXExample.manifest),
                models,
                { log },
                checkpoints,
                options(),
            ).compare(order)
        }.exceptionOrNull().assert().isInstanceOf(IOException::class.java)
        checkpoints.load().comparisonOf(order)!!.also {
            it.completed.assert().isFalse()
            it.compared.assert().isEqualTo(1)
            it.differing.assert().isEqualTo(0)
        }

        // The field is no longer ignored: the instance compared under the old map is compared again.
        Files.writeString(mapFile, NineXExample.MAP_TEXT)
        suspend fun current(restart: Boolean = false) = StateComparison(
            recorded(source),
            events,
            NineXExample.plan,
            models,
            { log },
            checkpoints,
            options(restart),
            { printed += it },
        ).compare(order)
        val resumed = current()
        printed.assert().contains(
            "example-service.order is compared in full again from the first instance: the map, a Manifest, the " +
                "classpath (the V10 models), the sample, the time zone or the tool changed since it stopped.",
        )
        reads.first().assert().isNull()
        resumed.compared.assert().isEqualTo(1)
        resumed.kinds.assert().isEqualTo(mapOf("legacyOnly" to 1L))
        resumed.passed.assert().isFalse()
        current(restart = true).kinds.assert().isEqualTo(resumed.kinds)
    }
}
