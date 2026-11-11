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
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The tail of a partition read in windows of `tailBatch` sequences: one read returns at most that many entries and
 * placeholders, however many aggregate collections the context has; a hole as long as the window, with streams past
 * it, is found by one probe past the window.
 */
class MongoTailWindowTest {
    private fun stream(type: AggregateType, id: String) = EventStreamRecord(
        ref = type.ref("tenant", id),
        version = 1,
        commandId = "command-${type.name}-$id",
        requestId = "request-${type.name}-$id",
        commandType = "${type.context}.${type.name}.Create",
        commitTime = 1L,
        events = listOf(EventRecord("${type.context}.${type.name}.Created", 1, "{}")),
        scope = Scope.NONE,
    )

    @Test
    fun `a tail read returns at most a window of entries, and reads on until it delivered every one`(): Unit =
        runBlocking {
            // The documents each tail read (an aggregation over the counters) returns, the counter among them.
            val replies = CopyOnWriteArrayList<Int>()
            val listener = object : CommandListener {
                override fun commandSucceeded(event: CommandSucceededEvent) {
                    val cursor = event.response.getDocument("cursor", null) ?: return
                    if (event.commandName == "aggregate" && cursor.getString("ns").value.endsWith("__partition")) {
                        replies += cursor.getArray("firstBatch").size
                    }
                }
            }
            listened(listener).use { client ->
                val context = MongoLogFixture.newContext()
                val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1, tailBatch = WINDOW)
                    .also { it.createSchema() }
                val types = listOf("order", "cart", "invoice").map { AggregateType(context, it) }
                // Three collections, their streams interleaved, each collection's in a block of sequences of its own.
                val appended = (1..3).flatMap { n -> types.map { stream(it, "s$n") } }
                appended.forEach { log.append(it) }
                replies.clear()
                val tailed = withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(appended.size).toList() }
                tailed.map { it.stream.ref }.assert().containsExactlyElementsOf(appended.map { it.ref })
                // A window of entries and the counter per read, never a window per collection.
                replies.assert().isNotEmpty().allMatch { it <= WINDOW + 1 }
            }
        }

    @Test
    fun `a stopped node's block longer than the window is found past it and sealed`(): Unit = runBlocking {
        val probes = AtomicInteger()
        listened(probes(probes)).use { client ->
            val context = MongoLogFixture.newContext()
            val type = AggregateType(context, "order")
            val stopped = MongoLogFixture.log(1, context)
            val owner = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1, tailBatch = WINDOW)
                .also { it.createSchema() }
            stopped.append(stream(type, "a"))
            // The owner's stream lands past the stopped node's block: 254 sequences missing, the window 2.
            owner.append(stream(type, "b"))
            // The tail finds the hole by a probe past its window, then seals the rest of the stopped node's block.
            val tailed = withTimeout(SEAL_TIMEOUT_MS) { owner.tail(0).take(2).toList() }
            tailed.map { it.stream.ref.id }.assert().containsExactly("a", "b")
            tailed.last().position.sequence.assert().isEqualTo(MongoEventLog.BLOCK + 1)
            probes.get().assert().isPositive()
        }
    }

    @Test
    fun `a tail stops at a missing sequence with streams only past the window`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val type = AggregateType(context, "order")
        val log = MongoLogFixture.log(1, context, tailBatch = WINDOW)
        for (n in 1..5) {
            log.append(stream(type, "s$n"))
        }
        // Sequences 2 and 3 gone: the window after 1 holds nothing, 4 and 5 lie past it.
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
            .getCollection(MongoNames.of("wow_log", context, "order"))
            .deleteMany(Filters.`in`("sequence", 2L, 3L))
        // Past the grace too, the sequence this log's own block holds is waited for, never stepped over.
        withTimeoutOrNull(STALL_MS) { log.tail(0).take(2).toList() }.assert().isNull()
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).first() }.position.sequence.assert().isEqualTo(1L)
    }

    @Test
    fun `no probe past the window is sent while the tail is at the head`(): Unit = runBlocking {
        val probes = AtomicInteger()
        listened(probes(probes)).use { client ->
            val context = MongoLogFixture.newContext()
            val type = AggregateType(context, "order")
            // A window as long as a block: what the counter reserved lies within it.
            val block = MongoEventLog.BLOCK.toInt()
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1, tailBatch = block)
                .also { it.createSchema() }
            for (n in 1..3) {
                log.append(stream(type, "s$n"))
            }
            // The three streams, then idle reads at the head until the timeout.
            withTimeoutOrNull(IDLE_MS) { log.tail(0).take(4).toList() }.assert().isNull()
            probes.get().assert().isZero()
        }
    }

    /** A client of the test replica set that reports its commands to [listener]. */
    private fun listened(listener: CommandListener): MongoClient = MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(WowContainers.mongoUrl))
            .addCommandListener(listener)
            .build(),
    )

    /** Counts in [count] the probes past a tail's window: the aggregations over the counters that limit a branch. */
    private fun probes(count: AtomicInteger) = object : CommandListener {
        override fun commandStarted(event: CommandStartedEvent) {
            val command = event.command
            if (event.commandName == "aggregate" &&
                command.getString("aggregate").value.endsWith("__partition") &&
                command.getArray("pipeline").toString().contains("\$limit")
            ) {
                count.incrementAndGet()
            }
        }
    }

    private companion object {
        const val WINDOW = 2
        const val TAIL_TIMEOUT_MS = 10_000L
        const val IDLE_MS = 1_000L
        val STALL_MS = MongoEventLog.STALL_GRACE.inWholeMilliseconds * 2
        val SEAL_TIMEOUT_MS = TAIL_TIMEOUT_MS + MongoEventLog.STALL_GRACE.inWholeMilliseconds * 2
    }
}
