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

@file:OptIn(InternalWowApi::class)

package me.ahoo.wow.benchmark.transfer

import com.mongodb.ConnectionString
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.springframework.core.env.Environment

/**
 * The component run of a log's group append on MongoDB (X-68): each operation appends one group, [CHAINS] aggregates'
 * streams of [VERSIONS] consecutive versions each, with one `appendAll`, as the node's append queue hands a contended
 * aggregate's batch over with the others'. With [conflict], another writer took the first version of each group's
 * first aggregate before the run, as a node that ran a command without the partition's lease does (X-60): the group's
 * insert stops at its first stream, the worst place for the others, and the counts show what their streams then cost.
 * An operation fails when a stream's outcome is not the one expected: a conflict for that aggregate's, a commit for
 * every other.
 *
 * It appends to a log of its own ([CONTEXT]), which no component of the node reads, on the node's client, whose round
 * trips are counted; the other writer has a client of its own, uncounted. The log is internal to Wow, which this file
 * opts in to: the benchmark's one use of an internal declaration, a user module otherwise (D216).
 */
class GroupAppendRun(
    private val conflict: Boolean,
    private val ops: Int,
    private val warmUpOps: Int,
    private val concurrency: Int,
) {
    private val type = AggregateType(CONTEXT, "chain")

    fun run(client: MongoClient, environment: Environment, counter: StoreCounter): Map<String, Any?> =
        MongoClients.create(ConnectionString(environment.getRequiredProperty("spring.mongodb.uri"))).use { other ->
            runBlocking(Dispatchers.Default) {
                val log = MongoEventLog(client, DATABASE, CONTEXT, PARTITIONS).also { it.createSchema() }
                val seedStarted = System.nanoTime()
                if (conflict) {
                    val writer = MongoEventLog(other, DATABASE, CONTEXT, PARTITIONS)
                    loop(warmUpOps + ops, concurrency) { index ->
                        val taken = writer.append(stream(index, 0, 1, origin = "elsewhere"))
                        check(taken is AppendOutcome.Committed) { "The other writer's stream of group $index: $taken" }
                    }
                }
                val seedSeconds = (System.nanoTime() - seedStarted) / NANOS_PER_SECOND
                mapOf(
                    "schema" to SCHEMA,
                    "scenario" to if (conflict) CONFLICT else GROUP,
                    "chains" to CHAINS,
                    "versions" to VERSIONS,
                    "streamsPerOp" to CHAINS * VERSIONS,
                    "ops" to ops,
                    "warmUpOps" to warmUpOps,
                    "concurrency" to concurrency,
                    "seedSeconds" to seedSeconds,
                ) + measure(counter, ops, warmUpOps, concurrency) { index -> group(log, index) } + ("note" to NOTE)
            }
        }

    /** Group [index]'s append, its first aggregate's streams first: the outcome it did not expect, if any. */
    private suspend fun group(log: MongoEventLog, index: Int): String? {
        val streams = (0 until CHAINS).flatMap { chain ->
            (1..VERSIONS).map { version -> stream(index, chain, version) to null }
        }
        return log.appendAll(streams).withIndex().firstNotNullOfOrNull { (at, outcome) ->
            val expected = if (conflict && at < VERSIONS) {
                outcome is AppendOutcome.Conflict
            } else {
                outcome is AppendOutcome.Committed
            }
            outcome.javaClass.simpleName.takeUnless { expected }
        }
    }

    /** Version [version] of group [group]'s aggregate [chain], a request of [origin]'s. */
    private fun stream(group: Int, chain: Int, version: Int, origin: String = "owner"): EventStreamRecord {
        val id = "g$group-$chain"
        val request = "$origin-$id-$version"
        return EventStreamRecord.of(
            ref = type.ref(TENANT, id),
            version = version,
            commandId = "command-$request",
            requestId = request,
            commandType = "$CONTEXT.chain.Act",
            commitTime = System.currentTimeMillis(),
            events = listOf(EventRecord.of("$CONTEXT.chain.Acted", 1, """{"version":$version}""")),
            scope = Scope.NONE,
        )
    }

    companion object {
        /** The group scenario: no stream refused. */
        const val GROUP = "group"

        /** The group scenario with each group's first aggregate's first version taken by another writer. */
        const val CONFLICT = "group-conflict"

        val SCENARIOS = setOf(GROUP, CONFLICT)

        /** Eight aggregates of four versions: a group of 32 streams, as many as one full kernel batch. */
        const val CHAINS = 8
        const val VERSIONS = 4

        private const val CONTEXT = "component-group"
        private const val DATABASE = "component_group"
        private const val PARTITIONS = 1
        private const val TENANT = "(0)"
    }
}
