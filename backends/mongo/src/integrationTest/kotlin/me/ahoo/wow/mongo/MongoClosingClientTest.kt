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

package me.ahoo.wow.mongo

import com.mongodb.client.MongoClients
import com.mongodb.client.internal.MongoClientImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.log.MongoEventLogOptions
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.query.MongoSnapshotQueryBackend
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A call that meets this node's client as it closes (#6747), against a real server: answered as the store
 * unavailable, which its caller retries, on another node once this one has stopped. [MongoFailuresTest] holds the
 * client once closed; this, the window before.
 */
class MongoClosingClientTest {
    private val context = MongoLogFixture.newContext("closing")
    private val cart = AggregateType(context, "cart")

    @Test
    fun `a call while the closing client ends its sessions should throw the retryable error`() {
        // A closing client shuts its server session pool first, ending its sessions with a round trip to the server,
        // and only then its cluster. A call in that window selects a server, then the pool refuses it its implicit
        // session, with the driver's other closed-client message.
        MongoClients.create(WowContainers.mongoUrl).use { client ->
            // The pool is closed through the driver's internal MongoClientImpl, no public API. If a driver upgrade
            // breaks this line, read the client's close() again and check MongoFailures.isClientClosed's messages.
            (client as MongoClientImpl).serverSessionPool.close()
            val database = MongoLogFixture.DATABASE
            val log = MongoEventLog(
                client,
                database,
                context,
                1,
                Dispatchers.IO,
                MongoEventLogOptions(sharding = MongoSharding.NONE),
            )
            val snapshots = MongoSnapshotStore(
                client,
                database,
                context,
                dispatcher = Dispatchers.IO,
                sharding = MongoSharding.NONE,
            )
            val calls: Map<String, suspend () -> Any?> = mapOf(
                "an append" to { log.append(stream()) },
                "a snapshot load" to { snapshots.load(cart.ref("t", "c-1")) },
                "a query" to {
                    MongoSnapshotQueryBackend.timed(null) {
                        client.getDatabase(database).getCollection("snapshots").find().first()
                    }
                },
            )
            calls.forEach { (call, run) ->
                val failure = assertThrows<StorageUnavailableException>(call) { runBlocking { run() } }
                generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<IllegalStateException>()
                    .map { it.message }.toList().assert().describedAs(call)
                    .contains("state should be: server session pool is open")
            }
        }
    }

    private fun stream() = EventStreamRecord.of(
        cart.ref("t", "c-1"),
        1,
        "command-1",
        "request-1",
        "$context.cart.Add",
        0L,
        listOf(EventRecord.of("$context.cart.Added", 1, "{}")),
        Scope.NONE,
    )
}
