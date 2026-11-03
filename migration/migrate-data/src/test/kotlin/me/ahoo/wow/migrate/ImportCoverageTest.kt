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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class ImportCoverageTest {
    private val order = NineXAggregate("example-service", "order")
    private val log = InMemoryEventLog(4)
    private val checkpoints = InMemoryCheckpointRepository()
    private val printed = mutableListOf<String>()

    // The full import starts at START: versions 1 and 2 were committed long before it, 3 and 4 while the tail
    // followed 9.x.
    private val streams = NineXExample.streams().filter { it.aggregate == order }.mapIndexed { index, stream ->
        stream.copy(createTime = if (index < 2) START - 10_000 else START + (index - 1) * 1_000L)
    }
    private val fifth = streams.last().copy(
        id = "stream-5",
        version = 5,
        commandId = "command-5",
        requestId = "command-5",
        createTime = START + 5_000,
    )
    private val source = InMemoryNineXEventSource(streams.take(2))

    private fun importer(options: ImportOptions = ImportOptions(lookbackMillis = LOOKBACK)) =
        EventStreamImporter(source, NineXExample.plan, { log }, checkpoints, options, { START })

    private fun coverage(
        options: CoverageOptions = CoverageOptions(lookbackMillis = LOOKBACK, marginMillis = 0),
        plan: MigrationPlan = NineXExample.plan,
    ) = ImportCoverage(source, plan, { log }, checkpoints, options, { printed += it })

    /** The full import, then the tail bringing in versions 3 and 4. */
    private suspend fun tailed() {
        importer().importAll(order)
        streams.drop(2).forEach(source::add)
        importer().tailOnce(order).imported.assert().isEqualTo(2)
    }

    private fun at(time: Long) = Instant.ofEpochMilli(time)

    @Test
    fun `the log holds what the tail imported, and the next check reads only what 9x committed since`() = runTest {
        tailed()
        coverage().check(order).also {
            it.passed.assert().isTrue()
            it.streams.assert().isEqualTo(2)
            it.instances.assert().isEqualTo(1)
        }
        printed.single().assert().isEqualTo(
            "example-service.order imported streams checked: 2 streams 9.x committed from ${at(START - LOOKBACK)} " +
                "to ${at(START + 2_000)}, of 1 instances; the log holds the latest of each",
        )
        checkpoints.load().coverageOf(order).assert().isEqualTo(CoverageCheckpoint(START + 2_000))
        source.add(fifth)
        importer().tailOnce(order).imported.assert().isEqualTo(1)
        // From where the last check ended, less the look-back: version 4 again, and version 5.
        coverage().check(order).also {
            it.passed.assert().isTrue()
            it.from.assert().isEqualTo(START + 2_000 - LOOKBACK)
            it.streams.assert().isEqualTo(2)
        }
        // A page a stream reads the same.
        coverage(CoverageOptions(pageSize = 1, lookbackMillis = LOOKBACK, marginMillis = 0, restart = true))
            .check(order).also {
                it.streams.assert().isEqualTo(3)
                it.instances.assert().isEqualTo(1)
            }
    }

    @Test
    fun `a stream the tail went past is missing, where the state comparison cannot see it`() = runTest {
        tailed()
        // Committed before the tail's checkpoint, it became visible after the tail had read past it.
        source.add(fifth.copy(createTime = START + 1_500))
        // 9.x's snapshot is at version 4, as its version_offset strategy leaves it: the states compare equal.
        val models = MigrationModels(listOf(NineXExample.orderModel, NineXExample.cartModel))
        val snapshots = InMemoryNineXSnapshotSource(NineXExample.snapshots(), source)
        StateComparison(
            snapshots,
            source,
            NineXExample.plan,
            models,
            { log },
            checkpoints
        ).compare(order).passed.assert().isTrue()
        val id = streams.first().aggregateId
        coverage().check(order).also {
            it.passed.assert().isFalse()
            it.missing.assert().isEqualTo(1)
            it.render().assert().endsWith("of 1 instances; 1 of them are missing from the log")
        }
        printed.first().assert().isEqualTo(
            "MISSING example-service.order $id v5: 9.x stream stream-5 (command command-5), committed at " +
                "${at(START + 1_500)}, is not in the log, which holds v4",
        )
        // Nothing is recorded: the next check reads it again, until it is imported.
        checkpoints.load().coverageOf(order).assert().isNull()
        coverage().check(order).passed.assert().isFalse()
        importer(ImportOptions(lookbackMillis = 10_000)).tailOnce(order).imported.assert().isEqualTo(1)
        coverage().check(order).passed.assert().isTrue()
    }

    @Test
    fun `another command at the version, or no version at all, is missing too`() = runTest {
        tailed()
        val id = streams.first().aggregateId
        val other = NineXAggregateId("order-other", id.tenantId)
        val target = InMemoryNineXEventSource(
            streams.map { it.copy(commandId = "replaced-${it.commandId}") } +
                streams.first().copy(id = "other-1", aggregateId = other, createTime = START + 1_000),
        )
        val options = CoverageOptions(lookbackMillis = LOOKBACK, marginMillis = 0)
        ImportCoverage(target, NineXExample.plan, { log }, checkpoints, options, { printed += it })
            .check(order).missing.assert().isEqualTo(2)
        printed.assert().contains(
            "MISSING example-service.order $id v4: 9.x stream ${streams.last().id} (command replaced-" +
                "${streams.last().commandId}), committed at ${at(START + 2_000)}, is not in the log, which holds " +
                "another command, ${streams.last().commandId}, at v4",
            "MISSING example-service.order $other v1: 9.x stream other-1 (command ${streams.first().commandId}), " +
                "committed at ${at(START + 1_000)}, is not in the log, which holds none of its versions",
        )
    }

    @Test
    fun `an excluded instance, the margin, and an aggregate the tail never followed are not checked`() = runTest {
        importer().importAll(order)
        coverage().check(order).also {
            it.passed.assert().isTrue()
            it.render().assert()
                .isEqualTo("example-service.order imported streams not checked: the tail never followed it")
        }
        streams.drop(2).forEach(source::add)
        importer().tailOnce(order)
        // Within the margin before the tail's checkpoint, the tail may still be reading what is visible late.
        coverage(CoverageOptions(lookbackMillis = LOOKBACK, marginMillis = 2_000)).check(order).also {
            it.to.assert().isEqualTo(START)
            it.streams.assert().isEqualTo(0)
        }
        coverage(CoverageOptions(lookbackMillis = 0, marginMillis = 2_001)).check(order).render().assert().isEqualTo(
            "example-service.order imported streams not checked: the tail's checkpoint is within 2001 ms of where " +
                "the check starts",
        )
        source.add(fifth.copy(aggregateId = NineXAggregateId("excluded", "(0)"), version = 1, createTime = START + 1))
        coverage(plan = NineXExample.planExcluding("excluded")).check(order).passed.assert().isTrue()
        coverage(
            CoverageOptions(lookbackMillis = LOOKBACK, marginMillis = 0, restart = true)
        ).check(order).passed.assert()
            .isFalse()
    }

    @Test
    fun `options refuse impossible values`() {
        assertThrows<IllegalArgumentException> { CoverageOptions(pageSize = 0) }
        assertThrows<IllegalArgumentException> { CoverageOptions(concurrency = 0) }
        assertThrows<IllegalArgumentException> { CoverageOptions(lookbackMillis = -1) }
        assertThrows<IllegalArgumentException> { CoverageOptions(marginMillis = -1) }
    }

    private companion object {
        const val START = 1_800_000_000_000L
        const val LOOKBACK = 500L
    }
}
