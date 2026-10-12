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

package me.ahoo.wow.leases

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.log.PartitionAppendQueue
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.mongodb.MongoDBContainer

/**
 * The partition owner's append queue on MongoDB (finding F1, decision D52): concurrent aggregates of one partition
 * appending through [PartitionAppendQueue] commit in groups without one aborted transaction, where appending to the
 * log directly aborts transactions on the partition's counter. Transactions are counted by the server.
 *
 * Not part of the gated spike: it runs with every `integrationTest`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoAppendQueueTest {
    private lateinit var container: MongoDBContainer
    private lateinit var client: MongoClient

    @BeforeAll
    fun start() {
        container = MongoDBContainer(IMAGE).withReplicaSet().also { it.start() }
        client = MongoClients.create(container.replicaSetUrl)
    }

    @AfterAll
    fun stop() {
        client.close()
        container.stop()
    }

    @Test
    fun `concurrent aggregates of one partition no longer abort each other's transactions`(): Unit = runBlocking {
        val directRun = run(log("direct"), "direct")
        directRun.committed.assert().isEqualTo(APPENDS)

        val queue = PartitionAppendQueue(log("queued"), AppendQueueOptions.MONGO)
        val queuedRun = run(queue, "queued")
        queue.close()
        println("MongoDB, one partition, $AGGREGATES aggregates: direct $directRun; queued $queuedRun")

        queuedRun.committed.assert().isEqualTo(APPENDS)
        queuedRun.aborted.assert().isZero()
        (queuedRun.transactions < APPENDS).assert().isTrue()
        (directRun.aborted > 0).assert().isTrue()
    }

    private fun log(context: String): MongoEventLog =
        MongoEventLog(client, DATABASE, context, 1).also { it.createSchema() }

    private data class Run(val committed: Int, val transactions: Long, val aborted: Long, val millis: Long)

    /** [AGGREGATES] aggregates of one partition, each appending [VERSIONS] versions in order, all at once. */
    private suspend fun run(log: EventLog, context: String): Run = coroutineScope {
        val type = AggregateType(context, "account")
        val before = transactions()
        val started = System.nanoTime()
        val committed = (0 until AGGREGATES).map { index ->
            async(Dispatchers.Default) {
                val ref = type.ref("tenant", "account-$index")
                (1..VERSIONS).count { version -> log.append(stream(ref, version)) is AppendOutcome.Committed }
            }
        }.awaitAll().sum()
        val after = transactions()
        Run(
            committed = committed,
            transactions = after.getLong("totalStarted") - before.getLong("totalStarted"),
            aborted = after.getLong("totalAborted") - before.getLong("totalAborted"),
            millis = (System.nanoTime() - started) / NANOS_PER_MILLI,
        )
    }

    private fun stream(ref: AggregateRef, version: Int) = EventStreamRecord(
        ref = ref,
        version = version,
        commandId = "command-${ref.id}-$version",
        requestId = "${ref.id}-$version",
        commandType = "${ref.type}.Deposit",
        commitTime = System.currentTimeMillis(),
        events = listOf(EventRecord("${ref.type}.Deposited", 1, """{"amount":1}""")),
        scope = Scope.NONE,
    )

    private fun transactions(): Document =
        client.getDatabase("admin").runCommand(Document("serverStatus", 1)).get("transactions", Document::class.java)

    private companion object {
        const val IMAGE = "mongo:7.0"
        const val DATABASE = "wow_append_queue"
        const val AGGREGATES = 32
        const val VERSIONS = 8
        const val APPENDS = AGGREGATES * VERSIONS
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
