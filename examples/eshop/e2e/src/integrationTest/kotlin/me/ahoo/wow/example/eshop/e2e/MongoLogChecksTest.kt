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

package me.ahoo.wow.example.eshop.e2e

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * The MongoDB arm's check of a log's integrity ([MongoLogChecks.violations]), run with mongosh as the GA verdict runs
 * it, against logs two nodes of one context write on the TCK's MongoDB.
 */
@OptIn(InternalWowApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoLogChecksTest {
    private val client: MongoClient = MongoClients.create(WowContainers.mongo.replicaSetUrl)

    @AfterAll
    fun close() = client.close()

    @Test
    fun `a block another node sealed takes no sequence twice, in a context of two aggregate types`(): Unit =
        runBlocking {
            val context = newContext()
            val stopped = log(context)
            val owner = log(context)
            stopped.append(stream(context, ORDER, "a"))
            owner.append(stream(context, ORDER, "b"))
            // The owner's tail waits at the stopped node's next sequence, then seals the rest of its block: a
            // placeholder at each sequence in each collection the block may write, the order's and the invoice's.
            tailed(owner, 2).assert().containsExactly("a", "b")
            // The node was not stopped after all: its late write meets a sealed position and lands in a new block.
            stopped.append(stream(context, INVOICE, "c")).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            tailed(owner, 3).assert().containsExactly("a", "b", "c")
            (sealed(context, ORDER) intersect sealed(context, INVOICE)).assert().isNotEmpty()
            violations(context).assert().isEmpty()
        }

    @Test
    fun `two streams at one sequence are a sequence taken twice`(): Unit = runBlocking {
        val context = newContext()
        val log = log(context)
        val sequence = (log.append(stream(context, ORDER, "a")) as AppendOutcome.Committed).position.sequence
        // A stream at the order's position in the invoice's collection, which no append writes.
        val order = collection(context, ORDER).find().first()!!
        collection(context, INVOICE).insertOne(Document(order).append("aggregate_id", "z").append("request_id", "z"))
        violations(context).assert().containsExactly(
            mapOf("kind" to "sequence taken twice", "partition" to "0", "sequence" to "$sequence", "times" to "2"),
        )
    }

    private fun newContext(): String = "eshop-checks-${UUID.randomUUID().toString().take(CONTEXT_MARK)}"

    /** A node of [context]'s log, of one partition, whose block lists both aggregate types' collections. */
    private fun log(context: String): MongoEventLog = MongoEventLog(client, DATABASE, context, 1).also { log ->
        log.createSchema()
        listOf(ORDER, INVOICE).forEach(log::createCollection)
    }

    private suspend fun tailed(log: MongoEventLog, count: Int): List<String> =
        withTimeout(TAIL_TIMEOUT) { log.tail(0).take(count).toList() }.map { it.stream.ref.id }

    private fun collection(context: String, aggregate: String): MongoCollection<Document> {
        val database = client.getDatabase(DATABASE)
        val name = database.getCollection(REGISTRY)
            .find(Filters.and(Filters.eq("context", context), Filters.eq("aggregate", aggregate)))
            .first()!!.getString("_id")
        return database.getCollection(name)
    }

    /** The sequences of [aggregate]'s collection that hold a sealed block's placeholder. */
    private fun sealed(context: String, aggregate: String): Set<Long> =
        collection(context, aggregate).find(Filters.eq("gap", "sealed")).map { it.getLong("sequence") }.toSet()

    /** [MongoLogChecks.violations] of [context], run with mongosh in the MongoDB container. */
    private fun violations(context: String): List<Map<String, String>> {
        val program = MongoLogChecks.program(SHELL_URI, DATABASE, MongoLogChecks.violations(context))
        val result = WowContainers.mongo.execInContainer("mongosh", "--quiet", "--nodb", "--eval", program)
        check(result.exitCode == 0) { "mongosh failed: ${result.stderr}${result.stdout}" }
        return json.readTree(result.stdout.trim().lineSequence().last()).values()
            .map { node -> node.properties().associate { (key, value) -> key to value.asString() } }
    }

    private fun stream(context: String, aggregate: String, id: String) = EventStreamRecord(
        ref = AggregateType(context, aggregate).ref("tenant", id),
        version = 1,
        commandId = "command-$id",
        requestId = "request-$id",
        commandType = "$context.$aggregate.Create",
        commitTime = 1L,
        events = listOf(EventRecord("$context.$aggregate.Created", 1, "{}")),
        scope = Scope.NONE,
    )

    companion object {
        private const val DATABASE = "eshop_checks"

        /** The log's registry of its aggregate collections, which the check reads too. */
        private const val REGISTRY = "wow_log_aggregate"
        private const val ORDER = "order"
        private const val INVOICE = "invoice"
        private const val CONTEXT_MARK = 8

        /** The container's own mongod, as mongosh reaches it inside the container. */
        private const val SHELL_URI = "mongodb://localhost:27017/?directConnection=true"

        /** The stall grace of the tail before it seals a block, and a margin. */
        private val TAIL_TIMEOUT = 30.seconds
    }
}
