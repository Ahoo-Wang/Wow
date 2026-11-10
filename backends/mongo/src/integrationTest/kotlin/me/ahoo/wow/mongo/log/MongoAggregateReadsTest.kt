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

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClients
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.CountedMongo
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.VersionPages
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.BsonDocument
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The reads of one aggregate's streams: by version, by key, and as a majority committed them. */
class MongoAggregateReadsTest : MongoLogFixtures() {
    @Test
    fun `reads outside an append read what a majority committed`(): Unit = runBlocking {
        // Finding A2-2: a tail reading `local` could deliver an entry a failover then rolls back.
        val reads = CopyOnWriteArrayList<BsonDocument>()
        val listener = object : CommandListener {
            override fun commandStarted(event: CommandStartedEvent) {
                val command = event.command.clone()
                if (event.commandName in setOf("find", "aggregate") && !command.containsKey("txnNumber")) {
                    reads += command
                }
            }
        }
        val settings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(WowContainers.mongoUrl))
            .addCommandListener(listener)
            .build()
        MongoClients.create(settings).use { client ->
            val context = MongoLogFixture.newContext()
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            val appended = stream("a", 1, aggregate = AggregateType(context, "order"))
            log.append(appended)
            reads.clear()
            withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).first() }
            log.head(0)
            log.read(appended.ref).toList()
            log.readEntries(appended.ref).toList()
            log.positionOf(appended.ref, 1)
            log.findByRequestId(appended.ref.type, "tenant", appended.requestId)
            // The head is one aggregation over the registry and the log's collections.
            val logReads = reads.filter { command ->
                val target = (command["find"] ?: command["aggregate"])?.asString()?.value.orEmpty()
                target.startsWith("wow_log__") || command.containsKey("aggregate") && target == MongoEventLog.REGISTRY
            }
            logReads.size.assert().isGreaterThanOrEqualTo(6)
            logReads.forEach { command ->
                command.getDocument("readConcern")?.getString("level")?.value.assert().isEqualTo("majority")
            }
        }
    }

    @Test
    fun `a request id reused in another partition commits, each aggregate's lookup finds its own`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val log = MongoLogFixture.log(4, context)
            val order = AggregateType(context, "order")
            val (a, b) = "p-1" to generateSequence(2) { it + 1 }.map { "p-$it" }
                .first { Partitioner.partitionOf(order.ref("tenant", it), 4) != Partitioner.partitionOf(order.ref("tenant", "p-1"), 4) }
            log.append(stream(a, 1, requestId = "reused", aggregate = order))
                .assert().isInstanceOf(AppendOutcome.Committed::class.java)
            log.append(stream(b, 1, requestId = "reused", aggregate = order))
                .assert().isInstanceOf(AppendOutcome.Committed::class.java)
            log.findByRequestId(order.ref("tenant", a), "reused")?.ref?.id.assert().isEqualTo(a)
            log.findByRequestId(order.ref("tenant", b), "reused")?.ref?.id.assert().isEqualTo(b)
            // Without the aggregate the lookup seeks every partition and finds one of them.
            log.findByRequestId(order, "tenant", "reused")?.ref?.id.assert().isIn(a, b)
        }

    @Test
    fun `a long read takes one find a page, a read within a page one, and no getMore`(): Unit = runBlocking {
        // X-80: a replay from version 1 once held a hot aggregate's whole history; now it holds a page.
        val context = MongoLogFixture.newContext()
        val log = MongoEventLog(CountedMongo.client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
        val type = AggregateType(context, "order")
        val count = 2 * VersionPages.SIZE + 3
        (1..count).chunked(GROUP_OF_STREAMS).forEach { versions ->
            log.appendAll(versions.map { stream("long", it, aggregate = type) to null })
        }
        (1..SHORT).forEach { log.append(stream("short", it, aggregate = type)) }
        val events = collection(context, "order")
        fun sent(read: suspend () -> Int): Pair<Int, List<String>> {
            val (size, sent) = CountedMongo.commandsOf { runBlocking { read() } }
            return size to sent.filter { it.collection == events || it.name == "getMore" }.map { it.name }
        }

        sent { log.read(type.ref("tenant", "long")).toList().size }.assert().isEqualTo(count to List(3) { "find" })
        sent { log.readEntries(type.ref("tenant", "long")).toList().size }
            .assert().isEqualTo(count to List(3) { "find" })
        sent { log.read(type.ref("tenant", "short")).toList().size }.assert().isEqualTo(SHORT to listOf("find"))
        sent { log.readEntries(type.ref("tenant", "short")).toList().size }.assert().isEqualTo(SHORT to listOf("find"))
        // A page that reaches the end of the range asked for is the last.
        sent { log.read(type.ref("tenant", "long"), 1, VersionPages.SIZE).toList().size }
            .assert().isEqualTo(VersionPages.SIZE to listOf("find"))
    }

    private companion object {
        const val GROUP_OF_STREAMS = 64
        const val SHORT = 5
    }
}
