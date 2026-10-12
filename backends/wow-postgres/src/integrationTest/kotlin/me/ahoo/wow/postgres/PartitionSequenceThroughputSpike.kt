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

package me.ahoo.wow.postgres

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spike H1 (documentation/designs/v10/decisions.md): can the partition sequence, assigned under the partition
 * counter's row lock, carry the commit rate? Runs only with `-Dwow.spike.h1=true`; writes its table to
 * `build/spike-h1.md`.
 */
class PartitionSequenceThroughputSpike {
    private data class Scenario(
        val name: String,
        val partitions: Int,
        val writers: Int,
        val ownerSerialized: Boolean,
        val plainInsert: Boolean = false,
    )

    private data class Result(val scenario: Scenario, val appendsPerSecond: Double, val p50Micros: Long, val p99Micros: Long)

    @Test
    fun measure() {
        assumeTrue(System.getProperty("wow.spike.h1") == "true", "Run with -Dwow.spike.h1=true")
        val scenarios = listOf(
            Scenario("single writer", partitions = 1, writers = 1, ownerSerialized = false),
            Scenario("one partition, 32 contending writers", partitions = 1, writers = 32, ownerSerialized = false),
            Scenario("64 partitions, 32 writers", partitions = 64, writers = 32, ownerSerialized = false),
            Scenario("64 partitions, 128 writers", partitions = 64, writers = 128, ownerSerialized = false),
            Scenario("64 partitions, 128 writers, owner-serialized", partitions = 64, writers = 128, ownerSerialized = true),
            Scenario(
                "baseline: plain insert, no partition counter",
                partitions = 64,
                writers = 128,
                ownerSerialized = false,
                plainInsert = true,
            ),
        )
        val results = scenarios.map { run(it) }
        val table = buildString {
            appendLine("| Scenario | Partitions | Writers | Appends/s | p50 (µs) | p99 (µs) |")
            appendLine("|---|---:|---:|---:|---:|---:|")
            results.forEach {
                appendLine(
                    "| ${it.scenario.name} | ${it.scenario.partitions} | ${it.scenario.writers} | " +
                        "${"%,.0f".format(it.appendsPerSecond)} | ${it.p50Micros} | ${it.p99Micros} |",
                )
            }
        }
        File("build/spike-h1.md").writeText(table)
        println(table)
    }

    private fun run(scenario: Scenario): Result {
        val context = PostgresFixture.newContext("h1")
        val log = PostgresFixture.log(scenario.partitions, context)
        val type = AggregateType(context, "aggregate")
        val locks = List(scenario.partitions) { Mutex() }
        val ids = AtomicInteger()
        val payload = """{"productId":"product-0001","quantity":1,"note":"a typical small event payload"}"""

        suspend fun append(count: Int, latencies: LongArray?) {
            repeat(count) {
                val index = ids.incrementAndGet()
                val ref = type.ref("tenant", "aggregate-$index")
                val stream = EventStreamRecord(
                    ref = ref,
                    version = 1,
                    commandId = "c-$index",
                    requestId = "r-$index",
                    commandType = "$type.Act",
                    commitTime = System.currentTimeMillis(),
                    events = listOf(EventRecord("$type.Happened", 1, payload)),
                    scope = Scope.NONE,
                )
                val started = System.nanoTime()
                val outcome = if (scenario.plainInsert) {
                    plainInsert(context, stream)
                } else if (scenario.ownerSerialized) {
                    locks[Partitioner.partitionOf(ref, scenario.partitions)].withLock { log.append(stream) }
                } else {
                    log.append(stream)
                }
                check(outcome is AppendOutcome.Committed) { "Append failed: $outcome" }
                latencies?.set(index % latencies.size, (System.nanoTime() - started) / NANOS_PER_MICRO)
            }
        }

        runBlocking(Dispatchers.Default) { repeat(scenario.writers) { launch { append(WARMUP / scenario.writers, null) } } }
        val latencies = LongArray(MEASURED)
        val started = System.nanoTime()
        runBlocking(Dispatchers.Default) {
            repeat(scenario.writers) { launch { append(MEASURED / scenario.writers, latencies) } }
        }
        val seconds = (System.nanoTime() - started) / NANOS_PER_SECOND
        val sorted = latencies.filter { it > 0 }.sorted()
        return Result(
            scenario = scenario,
            appendsPerSecond = (MEASURED / scenario.writers * scenario.writers) / seconds,
            p50Micros = sorted[sorted.size / 2],
            p99Micros = sorted[(sorted.size * 99) / 100],
        )
    }

    /** The database's own ceiling: one autocommitted insert of the same row, with no counter and no version check. */
    private suspend fun plainInsert(context: String, stream: EventStreamRecord): AppendOutcome =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            PostgresFixture.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO \"wow_log_${context.replace('-', '_')}\" (partition, sequence, aggregate_name, tenant_id, " +
                        "aggregate_id, version, command_id, request_id, command_type, commit_time, events, headers) " +
                        "VALUES (0, ?, ?, ?, ?, ?, ?, ?, ?, ?, '[]'::jsonb, '{}'::jsonb)",
                ).use { insert ->
                    insert.setLong(1, stream.requestId.removePrefix("r-").toLong())
                    insert.setString(2, stream.ref.type.name)
                    insert.setString(3, stream.ref.tenantId)
                    insert.setString(4, stream.ref.id)
                    insert.setInt(5, stream.version)
                    insert.setString(6, stream.commandId)
                    insert.setString(7, stream.requestId)
                    insert.setString(8, stream.commandType)
                    insert.setLong(9, stream.commitTime)
                    insert.executeUpdate()
                }
            }
            AppendOutcome.Committed(me.ahoo.wow.spi.log.LogPosition(0, 1))
        }

    private companion object {
        const val WARMUP = 2_048
        const val MEASURED = 16_384
        const val NANOS_PER_MICRO = 1_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
