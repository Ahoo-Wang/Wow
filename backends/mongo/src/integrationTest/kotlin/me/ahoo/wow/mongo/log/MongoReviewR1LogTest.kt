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

import com.mongodb.MongoSocketReadTimeoutException
import com.mongodb.MongoWriteException
import com.mongodb.ServerAddress
import com.mongodb.WriteError
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import org.bson.BsonDocument
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Deep review r1 (MongoDB backend): the log's failure paths. */
class MongoReviewR1LogTest : MongoLogFixtures() {
    /**
     * The stream's insert reaches a primary that applies it and loses its majority before it replicates: the append
     * is unknown, and the placeholder written at its sequence meets the stream still on that primary (a duplicate
     * key), so the log counts the sequence settled. The failover then rolls the stream back: the sequence holds
     * nothing, and no open block or unfilled mark lets the tail resolve it. The faults stand in for that primary: the
     * stream is never written (the rollback), and the placeholder's first insert is answered with the duplicate key
     * the old primary gave; a later placeholder at the sequence is written.
     */
    @Test
    fun `a sequence whose stream a failover rolled back after its placeholder met it does not stall the tail`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val lost = AtomicReference<Long?>(null)
            val answeredOnce = AtomicBoolean()
            val client = faulty(insert = { document, write ->
                when {
                    document.getString("aggregate_id") == "lost" -> {
                        lost.set(document.getLong("_id"))
                        throw MongoSocketReadTimeoutException("no reply", ServerAddress(), IOException("timed out"))
                    }

                    document.containsKey("gap") && document.getLong("_id") == lost.get() &&
                        answeredOnce.compareAndSet(false, true) -> throw MongoWriteException(
                        WriteError(
                            DUPLICATE_KEY,
                            "E11000 duplicate key error collection: wow_v10_log.x index: _id_ dup key: { _id: ${lost.get()} }",
                            BsonDocument(),
                        ),
                        ServerAddress(),
                        emptySet(),
                    )

                    else -> write()
                }
            })
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            val order = AggregateType(context, "order")
            log.append(stream("lost", 1, aggregate = order)).assert().isInstanceOf(AppendOutcome.Unknown::class.java)
            log.append(stream("kept", 1, aggregate = order)).assert().isInstanceOf(AppendOutcome.Committed::class.java)

            // The stream committed after the hole is delivered once the tail has resolved the empty sequence.
            val entries = withTimeout(TAIL_WAIT) { log.tail(0).take(1).toList() }

            entries.single().stream.ref.id.assert().isEqualTo("kept")
        }

    /**
     * `append-timeout` bounds an append so that "a command appending during an outage is answered before its wait
     * ends" (10 s by default), but an append that needs a new block first reserves it on the partition's counter
     * with the store's operation timeout (30 s by default). A transaction that holds the counter stands for a server
     * that does not answer that write.
     */
    @Test
    fun `an append that reserves a block while the counter does not answer fails within the append timeout`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val log = MongoEventLog(
                MongoLogFixture.client,
                MongoLogFixture.DATABASE,
                context,
                1,
                options = MongoEventLogOptions(appendTimeout = APPEND_TIMEOUT, operationTimeout = OPERATION_TIMEOUT),
            ).also { it.createSchema() }
            val order = AggregateType(context, "order")
            log.createCollection(order.name)
            val counters = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
                .getCollection(collection(context, "partition"))
            MongoLogFixture.client.startSession().use { session ->
                session.startTransaction()
                counters.updateOne(session, Filters.eq("_id", 0), Updates.inc("held", 1))
                try {
                    val started = TimeSource.Monotonic.markNow()
                    runCatching { log.append(stream("a", 1, aggregate = order)) }
                    val took = started.elapsedNow()

                    took.assert().isLessThan(APPEND_TIMEOUT * 3)
                } finally {
                    session.abortTransaction()
                }
            }
        }

    /**
     * A group's member whose position another node sealed is appended again alone, after the group's other members
     * committed ([EventLog.appendAll]: once a stream of the call may have committed, the call returns an outcome for
     * every stream). The re-append catches only a [com.mongodb.MongoException]: a driver whose client is closed
     * (shutdown) throws `IllegalStateException("state should be: open")`, and `appendNow` throws one itself after
     * [MongoEventLog.MAX_SEALED] seals in a row; either escapes `appendAll`, which then throws though a stream of it
     * committed.
     */
    @Test
    fun `a group whose re-append fails with a non-driver error still answers its committed member`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val order = AggregateType(context, "order")
            val sealed = AtomicBoolean()
            val reservations = java.util.concurrent.atomic.AtomicInteger()
            val client = faulty(
                many = { documents, insert ->
                    if (documents.size == 2 && documents.all { it.containsKey("version") } && sealed.compareAndSet(false, true)) {
                        // Another node seals the second member's position before the group's insert reaches it.
                        val second = documents[1]
                        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
                            .getCollection(collection(context, order.name))
                            .insertOne(
                                org.bson.Document("_id", second.getLong("_id"))
                                    .append("partition", second.getInteger("partition"))
                                    .append("sequence", second.getLong("sequence"))
                                    .append("gap", "sealed")
                                    .append("gap_node", "another"),
                            )
                    }
                    insert(documents)
                },
                reserve = {
                    // The group's own block is reserved; the re-append's reservation meets a closed client.
                    if (reservations.incrementAndGet() > 1) throw IllegalStateException("state should be: open")
                },
            )
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            log.createCollection(order.name)

            val outcomes = log.appendAll(
                listOf(Append.of(stream("a", 1, aggregate = order)), Append.of(stream("b", 1, aggregate = order))),
            )

            outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
            outcomes[1].assert().isInstanceOf(AppendOutcome.Unknown::class.java)
        }

    private companion object {
        const val DUPLICATE_KEY = 11000
        const val TAIL_WAIT = 10_000L
        val APPEND_TIMEOUT = 500.milliseconds
        val OPERATION_TIMEOUT = 6.seconds
    }
}
