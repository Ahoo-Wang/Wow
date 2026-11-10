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
import com.mongodb.event.CommandSucceededEvent
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The tail of a partition read in windows of `tailBatch` sequences: one read returns at most that many entries and
 * placeholders, however many aggregate collections the context has.
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
            val settings = MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(WowContainers.mongoUrl))
                .addCommandListener(listener)
                .build()
            MongoClients.create(settings).use { client ->
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

    private companion object {
        const val WINDOW = 2
        const val TAIL_TIMEOUT_MS = 10_000L
    }
}
