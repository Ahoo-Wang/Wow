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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Spike H1 (documentation/designs/v10/decisions.md): can the partition sequence, assigned under the partition
 * counter's row lock, carry the commit rate? And how long does a tail read of a partition take when its entries are
 * spread over the tables of several aggregate types (decision D162)? Runs only with `-Dwow.spike.h1=true`; writes its
 * tables to `build/spike-h1.md`.
 */
class PartitionSequenceThroughputSpike {
    private data class Scenario(
        val name: String,
        val partitions: Int,
        val writers: Int,
        val ownerSerialized: Boolean,
        val plainInsert: Boolean = false,
        val types: Int = 1,
    )

    private data class Result(val scenario: Scenario, val appendsPerSecond: Double, val p50Micros: Long, val p99Micros: Long)

    private data class TailResult(val types: Int, val p50Micros: Long, val p99Micros: Long, val catchUpPerSecond: Double)

    @Test
    fun measure() {
        assumeTrue(System.getProperty("wow.spike.h1") == "true", "Run with -Dwow.spike.h1=true")
        val scenarios = listOf(
            Scenario("single writer", partitions = 1, writers = 1, ownerSerialized = false),
            Scenario("one partition, 32 contending writers", partitions = 1, writers = 32, ownerSerialized = false),
            Scenario("64 partitions, 32 writers", partitions = 64, writers = 32, ownerSerialized = false),
            Scenario("64 partitions, 128 writers", partitions = 64, writers = 128, ownerSerialized = false),
            Scenario("64 partitions, 128 writers", partitions = 64, writers = 128, ownerSerialized = false, types = 5),
            Scenario("64 partitions, 128 writers, owner-serialized", partitions = 64, writers = 128, ownerSerialized = true),
            Scenario(
                "64 partitions, 128 writers, owner-serialized",
                partitions = 64,
                writers = 128,
                ownerSerialized = true,
                types = 5,
            ),
            Scenario(
                "baseline: plain insert, no partition counter",
                partitions = 64,
                writers = 128,
                ownerSerialized = false,
                plainInsert = true,
            ),
        )
        val results = scenarios.map { run(it) }
        val tails = listOf(1, 5).map { tail(it) }
        val table = buildString {
            appendLine("| Scenario | Partitions | Writers | Aggregate types | Appends/s | p50 (µs) | p99 (µs) |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|")
            results.forEach {
                appendLine(
                    "| ${it.scenario.name} | ${it.scenario.partitions} | ${it.scenario.writers} | ${it.scenario.types} | " +
                        "${"%,.0f".format(it.appendsPerSecond)} | ${it.p50Micros} | ${it.p99Micros} |",
                )
            }
            appendLine()
            appendLine(
                "| Tail of one partition of $TAIL_ENTRIES entries | Aggregate types | First batch p50 (µs) | " +
                    "First batch p99 (µs) | Catch-up entries/s |",
            )
            appendLine("|---|---:|---:|---:|---:|")
            tails.forEach {
                appendLine(
                    "| $TAIL_BATCH entries after a random position | ${it.types} | ${it.p50Micros} | ${it.p99Micros} | " +
                        "${"%,.0f".format(it.catchUpPerSecond)} |",
                )
            }
        }
        File("build/spike-h1.md").writeText(table)
        println(table)
    }

    private fun stream(type: AggregateType, index: Int) = EventStreamRecord(
        ref = type.ref("tenant", "aggregate-$index"),
        version = 1,
        commandId = "c-$index",
        requestId = "r-$index",
        commandType = "$type.Act",
        commitTime = System.currentTimeMillis(),
        events = listOf(EventRecord("$type.Happened", 1, PAYLOAD)),
        scope = Scope.NONE,
    )

    /** One aggregate type, `aggregate`, or [count] of them, `aggregate0` and on. */
    private fun typesOf(context: String, count: Int): List<AggregateType> = if (count == 1) {
        listOf(AggregateType(context, "aggregate"))
    } else {
        (0 until count).map { AggregateType(context, "aggregate$it") }
    }

    private fun run(scenario: Scenario): Result {
        val context = PostgresFixture.newContext("h1")
        val types = typesOf(context, scenario.types)
        val log = PostgresFixture.log(scenario.partitions, context, types.map { it.name })
        val locks = List(scenario.partitions) { Mutex() }
        val ids = AtomicInteger()

        suspend fun append(count: Int, latencies: LongArray?) {
            repeat(count) {
                val index = ids.incrementAndGet()
                val stream = stream(types[index % types.size], index)
                val started = System.nanoTime()
                val outcome = if (scenario.plainInsert) {
                    plainInsert(context, stream)
                } else if (scenario.ownerSerialized) {
                    locks[Partitioner.partitionOf(stream.ref, scenario.partitions)].withLock { log.append(stream) }
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

    /**
     * Fills one partition with [TAIL_ENTRIES] entries spread round-robin over [typeCount] aggregate types, then reads
     * it back: the latency of the first batch after a random position, and the rate of reading it all from the start.
     */
    private fun tail(typeCount: Int): TailResult {
        val context = PostgresFixture.newContext("h1-tail")
        val log = PostgresFixture.log(1, context)
        val types = typesOf(context, typeCount)
        val ids = AtomicInteger()
        runBlocking(Dispatchers.Default) {
            repeat(TAIL_WRITERS) {
                launch {
                    repeat(TAIL_ENTRIES / TAIL_WRITERS) {
                        val index = ids.incrementAndGet()
                        val outcome = log.append(stream(types[index % types.size], index))
                        check(outcome is AppendOutcome.Committed) { "Append failed: $outcome" }
                    }
                }
            }
        }
        val random = Random(SEED)
        val positions = 1L until (TAIL_ENTRIES - TAIL_BATCH).toLong()
        val latencies = runBlocking {
            repeat(TAIL_WARMUP) { log.tail(0, positions.random(random)).first() }
            (1..TAIL_SAMPLES).map {
                val after = positions.random(random)
                val started = System.nanoTime()
                log.tail(0, after).first()
                (System.nanoTime() - started) / NANOS_PER_MICRO
            }.sorted()
        }
        val total = TAIL_ENTRIES / TAIL_WRITERS * TAIL_WRITERS
        val started = System.nanoTime()
        runBlocking {
            var seen = 0
            log.tail(0).first { ++seen == total }
        }
        val seconds = (System.nanoTime() - started) / NANOS_PER_SECOND
        return TailResult(typeCount, latencies[latencies.size / 2], latencies[(latencies.size * 99) / 100], total / seconds)
    }

    /** The database's own ceiling: one autocommitted insert of the same row, with no counter and no version check. */
    private suspend fun plainInsert(context: String, stream: EventStreamRecord): AppendOutcome =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            PostgresFixture.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO \"wow_log__${context.replace('-', '_')}__aggregate\" (partition, sequence, tenant_id, " +
                        "aggregate_id, version, command_id, request_id, command_type, commit_time, events, headers) " +
                        "VALUES (0, ?, ?, ?, ?, ?, ?, ?, ?, '[]'::jsonb, '{}'::jsonb)",
                ).use { insert ->
                    insert.setLong(1, stream.requestId.removePrefix("r-").toLong())
                    insert.setString(2, stream.ref.tenantId)
                    insert.setString(3, stream.ref.id)
                    insert.setInt(4, stream.version)
                    insert.setString(5, stream.commandId)
                    insert.setString(6, stream.requestId)
                    insert.setString(7, stream.commandType)
                    insert.setLong(8, stream.commitTime)
                    insert.executeUpdate()
                }
            }
            AppendOutcome.Committed(LogPosition(0, 1))
        }

    private companion object {
        const val WARMUP = 2_048
        const val MEASURED = 16_384
        const val TAIL_ENTRIES = 20_000
        const val TAIL_WRITERS = 32
        const val TAIL_BATCH = 500
        const val TAIL_WARMUP = 20
        const val TAIL_SAMPLES = 200
        const val SEED = 42
        const val PAYLOAD = """{"productId":"product-0001","quantity":1,"note":"a typical small event payload"}"""
        const val NANOS_PER_MICRO = 1_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
