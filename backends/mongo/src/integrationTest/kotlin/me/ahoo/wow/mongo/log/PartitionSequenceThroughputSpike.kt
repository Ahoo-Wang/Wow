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

package me.ahoo.wow.mongo.log

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spike H1 (documentation/designs/decisions.md), MongoDB half: can the partition sequence, assigned by a
 * multi-document transaction that bumps the partition's counter, carry the commit rate? Runs only with `-Dwow-test.spike.h1=true`; writes its table to
 * `build/spike-h1-mongo.md`.
 */
class PartitionSequenceThroughputSpike {
    private data class Scenario(
        val name: String,
        val partitions: Int,
        val writers: Int,
        val ownerSerialized: Boolean,
        val plainInsert: Boolean = false,
        val groupCommit: Boolean = false,
    )

    private data class Result(val scenario: Scenario, val appendsPerSecond: Double, val p50Micros: Long, val p99Micros: Long)

    @Test
    fun measure() {
        assumeTrue(System.getProperty("wow-test.spike.h1") == "true", "Run with -Dwow-test.spike.h1=true")
        val scenarios = listOf(
            Scenario("single writer", partitions = 1, writers = 1, ownerSerialized = false),
            Scenario("one partition, 32 contending writers", partitions = 1, writers = 32, ownerSerialized = false),
            Scenario("64 partitions, 32 writers", partitions = 64, writers = 32, ownerSerialized = false),
            Scenario("64 partitions, 128 writers", partitions = 64, writers = 128, ownerSerialized = false),
            Scenario("64 partitions, 128 writers, owner-serialized", partitions = 64, writers = 128, ownerSerialized = true),
            Scenario("one partition, 32 writers, owner group commit", 1, 32, ownerSerialized = false, groupCommit = true),
            Scenario("64 partitions, 128 writers, owner group commit", 64, 128, ownerSerialized = false, groupCommit = true),
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
        File("build/spike-h1-mongo.md").writeText(table)
        println(table)
    }

    private fun run(scenario: Scenario): Result {
        val context = MongoLogFixture.newContext("h1")
        val log = MongoLogFixture.log(scenario.partitions, context, listOf("aggregate"))
        val type = AggregateType(context, "aggregate")
        val locks = List(scenario.partitions) { Mutex() }
        val committerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val committer = if (scenario.groupCommit) GroupCommitter(log, scenario.partitions, committerScope) else null
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
                val outcome = if (committer != null) {
                    committer.append(stream)
                } else if (scenario.plainInsert) {
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
        committerScope.cancel()
        val sorted = latencies.filter { it > 0 }.sorted()
        return Result(
            scenario = scenario,
            appendsPerSecond = (MEASURED / scenario.writers * scenario.writers) / seconds,
            p50Micros = sorted[sorted.size / 2],
            p99Micros = sorted[(sorted.size * 99) / 100],
        )
    }

    /** The database's own ceiling: one insert of the same document, with no transaction, counter or version check. */
    private suspend fun plainInsert(context: String, stream: EventStreamRecord): AppendOutcome =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
                .getCollection("wow_log__" + context.replace('-', '_') + "__aggregate")
                .insertOne(
                    org.bson.Document("partition", 0)
                        .append("sequence", stream.requestId.removePrefix("r-").toLong())
                        .append("tenant_id", stream.ref.tenantId)
                        .append("aggregate_id", stream.ref.id)
                        .append("version", stream.version)
                        .append("command_id", stream.commandId)
                        .append("request_id", stream.requestId)
                        .append("command_type", stream.commandType)
                        .append("commit_time", stream.commitTime)
                        .append("events", listOf<org.bson.Document>()),
                )
            AppendOutcome.Committed(me.ahoo.wow.spi.log.LogPosition(0, 1))
        }

    /**
     * A partition owner's append queue: one consumer per partition takes what is queued (up to [maxBatch]) and
     * appends it with one [EventLog.appendAll], the shape the runtime gives it (finding F1).
     */
    private class GroupCommitter(
        private val log: EventLog,
        private val partitions: Int,
        scope: CoroutineScope,
        private val maxBatch: Int = 64,
    ) {
        private val queues = List(partitions) {
            Channel<Pair<EventStreamRecord, CompletableDeferred<AppendOutcome>>>(Channel.UNLIMITED)
        }

        init {
            queues.forEach { queue ->
                scope.launch {
                    while (true) {
                        val batch = mutableListOf(queue.receive())
                        while (batch.size < maxBatch) {
                            batch += queue.tryReceive().getOrNull() ?: break
                        }
                        val outcomes = log.appendAll(batch.map { it.first to null })
                        batch.zip(outcomes).forEach { (pending, outcome) -> pending.second.complete(outcome) }
                    }
                }
            }
        }

        suspend fun append(stream: EventStreamRecord): AppendOutcome {
            val outcome = CompletableDeferred<AppendOutcome>()
            queues[Partitioner.partitionOf(stream.ref, partitions)].send(stream to outcome)
            return outcome.await()
        }
    }

    private companion object {
        const val WARMUP = 2_048
        const val MEASURED = 16_384
        const val NANOS_PER_MICRO = 1_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
