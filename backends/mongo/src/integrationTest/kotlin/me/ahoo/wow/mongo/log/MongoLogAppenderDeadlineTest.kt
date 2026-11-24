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

import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.AppendOutcome
import org.bson.Document
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * An append's round trips, all of them, within `append-timeout` from the call's start (#6744): so that a command
 * appending during an outage is answered before its wait ends. A transaction that holds a document stands for a server
 * that does not answer a write that needs it, and a `$where` that sleeps for one that does not answer a read.
 */
class MongoLogAppenderDeadlineTest : MongoLogFixtures() {
    @Test
    fun `an append that reserves a block while the counter does not answer fails within the append timeout`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val log = log(context)
            holding(counters(context), Filters.eq("_id", 0)) {
                val started = TimeSource.Monotonic.markNow()
                // The reservation fails: nothing of the append was written, so it is thrown.
                runCatching { log.append(stream("a", 1, aggregate = AggregateType(context, "order"))) }
                    .exceptionOrNull().assert().isNotNull()

                // Before, the reservation waited for the operation timeout (6 s here).
                started.elapsedNow().assert().isLessThan(APPEND_TIMEOUT * 2)
            }
        }

    @Test
    fun `an append whose read of the previous stream gets no answer fails within the append timeout`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val order = AggregateType(context, "order")
            // The aggregate's first stream, appended by another node: this log does not remember it, so it reads it.
            log(context).append(stream("a", 1, aggregate = order)).assert()
                .isInstanceOf(AppendOutcome.Committed::class.java)
            val slow = AtomicBoolean()
            val sleeping = Filters.where("sleep($SLOW_MILLIS) || true")
            val log = log(context, faulty(slowed = { if (slow.get()) Filters.and(it, sleeping) else it }))
            slow.set(true)
            val started = TimeSource.Monotonic.markNow()
            // The read fails: nothing of the append was written, so it is thrown.
            runCatching { log.append(stream("a", 2, aggregate = order)) }.exceptionOrNull().assert().isNotNull()

            // Before, the read waited for the server (3 s here), within the operation timeout.
            started.elapsedNow().assert().isLessThan(APPEND_TIMEOUT * 1.5)
        }

    @Test
    fun `an append that waits for its partition's sequencer behind another's reservation counts the wait`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val log = log(context)
            holding(counters(context), Filters.eq("_id", 0)) {
                // Two appends of one partition at once: one reserves a block while the counter does not answer, the
                // other waits for the partition's sequencer meanwhile.
                val took = runBlocking(Dispatchers.Default) {
                    listOf("a", "b").map { id ->
                        async {
                            val started = TimeSource.Monotonic.markNow()
                            runCatching { log.append(stream(id, 1, aggregate = AggregateType(context, "order"))) }
                            started.elapsedNow()
                        }
                    }.awaitAll()
                }

                // With a bound from its first round trip, the one that waited would reserve for a whole bound after.
                took.forEach { it.assert().isLessThan(APPEND_TIMEOUT * 1.5) }
            }
        }

    @Test
    fun `an append's later round trips take what is left of its bound, not a bound each`(): Unit = runBlocking {
        // The reservation waits 700 ms of the 1 s for the counter; the insert then meets its position held, and has
        // what is left. With a bound each, the insert would take 1 s more and its placeholder another.
        val context = MongoLogFixture.newContext()
        val log = log(context)
        val order = collection(context, "order")
        val first = MongoEventLog.positionId(0, 1)
        holding(MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(order), insert = first) {
            val started = TimeSource.Monotonic.markNow()
            val outcome = holding(counters(context), Filters.eq("_id", 0), releaseAfter = HELD) {
                log.append(stream("a", 1, aggregate = AggregateType(context, "order")))
            }

            outcome.assert().isInstanceOf(AppendOutcome.Unknown::class.java)
            started.elapsedNow().assert().isLessThan(APPEND_TIMEOUT + HELD)
        }
    }

    private fun log(context: String, client: MongoClient = MongoLogFixture.client) = MongoEventLog(
        client,
        MongoLogFixture.DATABASE,
        context,
        1,
        options = MongoEventLogOptions(appendTimeout = APPEND_TIMEOUT, operationTimeout = OPERATION_TIMEOUT),
    ).also {
        it.createSchema()
        it.createCollection("order")
    }

    private fun counters(context: String) =
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection(context, "partition"))

    /**
     * Runs [block] while a transaction holds the document [filter] matches in [collection] (an update of it), or the
     * `_id` [insert] (an insert of it): a write that needs it waits. With [releaseAfter], the transaction ends then.
     */
    private inline fun <T> holding(
        collection: com.mongodb.client.MongoCollection<Document>,
        filter: org.bson.conversions.Bson? = null,
        insert: Long? = null,
        releaseAfter: Duration? = null,
        block: () -> T,
    ): T = MongoLogFixture.client.startSession().use { session: ClientSession ->
        session.startTransaction()
        filter?.let { collection.updateOne(session, it, Updates.inc("held", 1)) }
        insert?.let { collection.insertOne(session, Document("_id", it).append("partition", 0).append("sequence", 1L)) }
        val release = releaseAfter?.let {
            thread {
                Thread.sleep(it.inWholeMilliseconds)
                session.abortTransaction()
            }
        }
        try {
            block()
        } finally {
            release?.join() ?: session.abortTransaction()
        }
    }

    private companion object {
        val APPEND_TIMEOUT = 1.seconds
        val OPERATION_TIMEOUT = 6.seconds
        val HELD = 700.milliseconds
        const val SLOW_MILLIS = 3_000
    }
}
