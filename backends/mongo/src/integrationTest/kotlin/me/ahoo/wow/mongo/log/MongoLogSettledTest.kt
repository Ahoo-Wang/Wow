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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoSocketReadTimeoutException
import com.mongodb.MongoWriteException
import com.mongodb.ServerAddress
import com.mongodb.WriteError
import com.mongodb.bulk.BulkWriteError
import com.mongodb.bulk.BulkWriteResult
import com.mongodb.bulk.WriteConcernError
import com.mongodb.client.MongoClient
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.CountedMongo
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import org.bson.BsonDocument
import org.bson.Document
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What settles a sequence (#6745): this node's own write acknowledged by a majority, or a majority read that finds a
 * stream or placeholder at the position after a duplicate key. A duplicate key alone is what the primary that answered
 * holds, which a failover may roll back. One-member replica sets cannot roll back: each test's faults stand in for
 * such a primary, a write that never lands and a reply that says the position is taken (or a write concern error),
 * once; the store then answers as itself. Each log has one partition.
 */
class MongoLogSettledTest : MongoLogFixtures() {
    private val logContext = MongoLogFixture.newContext()
    private val order = AggregateType(logContext, "order")

    private fun logOf(client: MongoClient = MongoLogFixture.client) =
        MongoEventLog(client, MongoLogFixture.DATABASE, logContext, 1).also {
            it.createSchema()
            it.createCollection(order.name)
        }

    private val orders get() = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
        .getCollection(collection(logContext, order.name))

    /** The duplicate key a primary answers for [id] at the position index. */
    private fun duplicate(id: Long) = "E11000 duplicate key error collection: ${MongoLogFixture.DATABASE}.x " +
        "index: _id_ dup key: { _id: $id }"

    private fun positionTaken(id: Long) = MongoWriteException(
        WriteError(DUPLICATE_KEY, duplicate(id), BsonDocument()),
        ServerAddress(),
        emptySet(),
    )

    private fun bulk(errors: List<BulkWriteError>, concern: WriteConcernError? = null) = MongoBulkWriteException(
        BulkWriteResult.acknowledged(0, 0, 0, 0, emptyList(), emptyList()),
        errors,
        concern,
        ServerAddress(),
        emptySet(),
    )

    /** The bulk reply's duplicate keys at each of [indexes], as a primary that holds their positions answers. */
    private fun takenAt(
        indexes: IntRange
    ) = indexes.map { BulkWriteError(DUPLICATE_KEY, duplicate(0), BsonDocument(), it) }

    private fun lost(): Nothing = throw MongoSocketReadTimeoutException(
        "no reply",
        ServerAddress(),
        IOException("lost")
    )

    /** The streams of the log's partition the tail delivers, [count] of them, within the grace and a margin. */
    private suspend fun delivered(log: MongoEventLog, count: Int) = tailed(log, count).map { it.stream.ref.id }

    @Test
    fun `an unknown append's placeholder that meets a position a majority does not hold leaves it to the tail`(): Unit =
        runBlocking {
            val position = MongoEventLog.positionId(0, 1)
            val answered = AtomicBoolean()
            val log = logOf(
                faulty(insert = { document, write ->
                    when {
                        document.getString("aggregate_id") == "lost" -> lost()
                        document.containsKey("gap") && document.getLong("_id") == position &&
                            answered.compareAndSet(false, true) -> throw positionTaken(position)

                        else -> write()
                    }
                }),
            )
            log.append(stream("lost", 1, aggregate = order)).assert().isInstanceOf(AppendOutcome.Unknown::class.java)
            log.append(stream("kept", 1, aggregate = order)).assert().isInstanceOf(AppendOutcome.Committed::class.java)

            delivered(log, 1).assert().containsExactly("kept")
        }

    @Test
    fun `a group's placeholders that meet positions a majority does not hold leave them to the tail`(): Unit =
        runBlocking {
            val answered = AtomicBoolean()
            val log = logOf(
                faulty(many = { documents, insert ->
                    when {
                        documents.any { it.getString("aggregate_id")?.startsWith("lost") == true } -> lost()
                        documents.all { it.containsKey("gap") } && answered.compareAndSet(false, true) ->
                            throw bulk(takenAt(documents.indices))

                        else -> insert(documents)
                    }
                }),
            )
            log.appendAll(
                listOf(
                    Append.of(stream("lost-a", 1, aggregate = order)),
                    Append.of(stream("lost-b", 1, aggregate = order))
                )
            )
                .forEach { it.assert().isInstanceOf(AppendOutcome.Unknown::class.java) }
            log.append(stream("kept", 1, aggregate = order)).assert().isInstanceOf(AppendOutcome.Committed::class.java)

            delivered(log, 1).assert().containsExactly("kept")
        }

    @Test
    fun `a closing that meets a first sequence a majority does not hold is closed again`(): Unit = runBlocking {
        // This node's block takes `a` at 1; another node's, `b` at 257. The tail closes 2 to 256, and its range
        // placeholder is answered as taken once.
        val answered = AtomicBoolean()
        val log = logOf(
            faulty(insert = { document, write ->
                if (document.getString("gap") == MongoEventLog.GAP_CLOSED && answered.compareAndSet(false, true)) {
                    throw positionTaken(document.getLong("_id"))
                }
                write()
            }),
        )
        log.append(stream("a", 1, aggregate = order))
        MongoLogFixture.log(1, logContext).append(stream("b", 1, aggregate = order))

        delivered(log, 2).assert().containsExactly("a", "b")
        answered.get().assert().isTrue()
    }

    @Test
    fun `an append whose position was taken by what a majority does not hold leaves it to the tail`(): Unit =
        runBlocking {
            // Another node sealed this node's block (its placeholders at 2 to 256 are there), but the one at 1, which
            // the stream's insert met, was rolled back: 1 holds nothing.
            val answered = AtomicBoolean()
            val log = logOf(
                faulty(insert = { document, write ->
                    if (document.getString("aggregate_id") == "a" && answered.compareAndSet(false, true)) {
                        orders.insertMany((2L..MongoEventLog.BLOCK).map { sealed(it) })
                        throw positionTaken(document.getLong("_id"))
                    }
                    write()
                }),
            )
            log.append(stream("a", 1, aggregate = order)).assert().isInstanceOf(AppendOutcome.Committed::class.java)

            delivered(log, 1).assert().containsExactly("a")
        }

    @Test
    fun `a group member whose position was taken by what a majority does not hold leaves it to the tail`(): Unit =
        runBlocking {
            // The group's insert writes `a` at 1 and meets 2 taken: another node sealed this node's block (3 to 256
            // are there), but its placeholder at 2 was rolled back. `b` is appended again alone, from a new block.
            val answered = AtomicBoolean()
            val log = logOf(
                faulty(many = { documents, insert ->
                    if (documents.size == 2 && documents.all { it.containsKey("version") } &&
                        answered.compareAndSet(false, true)
                    ) {
                        insert(documents.take(1))
                        orders.insertMany((3L..MongoEventLog.BLOCK).map { sealed(it) })
                        val taken = documents[1].getLong("_id")
                        throw bulk(listOf(BulkWriteError(DUPLICATE_KEY, duplicate(taken), BsonDocument(), 1)))
                    }
                    insert(documents)
                }),
            )
            log.appendAll(
                listOf(Append.of(stream("a", 1, aggregate = order)), Append.of(stream("b", 1, aggregate = order)))
            )
                .forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }

            delivered(log, 2).assert().containsExactly("a", "b")
            answered.get().assert().isTrue()
        }

    @Test
    fun `a seal whose duplicates a majority does not hold keeps the block listed, and seals it again`(): Unit =
        runBlocking {
            // A stopped node took 1 and was writing 2 on a primary that lost it: the seal's first insert is answered
            // with 2 taken, the rest written.
            val answered = AtomicBoolean()
            val owner = logOf(
                faulty(many = { documents, insert ->
                    if (documents.all { it.getString("gap") == MongoEventLog.GAP_SEALED } && answered.compareAndSet(false, true)) {
                        insert(documents.drop(1))
                        throw bulk(listOf(BulkWriteError(DUPLICATE_KEY, duplicate(2), BsonDocument(), 0)))
                    }
                    insert(documents)
                }),
            )
            MongoLogFixture.log(1, logContext).append(stream("a", 1, aggregate = order))
            owner.append(stream("b", 1, aggregate = order))

            delivered(owner, 2).assert().containsExactly("a", "b")
            answered.get().assert().isTrue()
        }

    @Test
    fun `a seal whose write a majority did not acknowledge keeps the block listed, and seals it again`(): Unit =
        runBlocking {
            // The primary applied the seal and lost it before it replicated: a write concern error, nothing kept.
            val answered = AtomicBoolean()
            val owner = logOf(
                faulty(many = { documents, insert ->
                    if (documents.all { it.getString("gap") == MongoEventLog.GAP_SEALED } && answered.compareAndSet(false, true)) {
                        throw bulk(
                            emptyList(),
                            WriteConcernError(WRITE_CONCERN_FAILED, "", "primary stepped down", BsonDocument())
                        )
                    }
                    insert(documents)
                }),
            )
            MongoLogFixture.log(1, logContext).append(stream("a", 1, aggregate = order))
            owner.append(stream("b", 1, aggregate = order))

            delivered(owner, 2).assert().containsExactly("a", "b")
            answered.get().assert().isTrue()
        }

    @Test
    fun `a committed append and a committed group read nothing back`(): Unit = runBlocking {
        // The committed path makes the same round trips as before: the majority reads are on duplicate keys alone.
        val log = MongoEventLog(CountedMongo.client, MongoLogFixture.DATABASE, logContext, 1).also {
            it.createSchema()
            it.createCollection(order.name)
        }
        log.append(stream("warm", 1, aggregate = order))
        val (_, sent) = CountedMongo.commandsOf {
            runBlocking {
                log.append(stream("one", 1, aggregate = order))
                log.appendAll(
                    listOf(Append.of(stream("x", 1, aggregate = order)), Append.of(stream("y", 1, aggregate = order)))
                )
            }
        }

        sent.map { it.name }.assert().containsExactly("insert", "insert")
    }

    @Test
    fun `a tail that waits at a sequence no open block holds says how to repair it`(): Unit = runBlocking {
        // A log a pre-release left: 2 settled and then emptied, its block no longer listed. Other nodes' blocks are, one
        // below it and one past it.
        val log = logOf()
        for (id in listOf("a", "b", "c")) log.append(stream(id, 1, aggregate = order))
        orders.deleteOne(Filters.and(Filters.eq("partition", 0), Filters.eq("_id", MongoEventLog.positionId(0, 2))))
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection(logContext, "partition"))
            .updateOne(
                Filters.eq("_id", 0),
                Updates.set("blocks", listOf(block("below", 1, 1), block("past", BEYOND, BEYOND)))
            )
        val logger = LoggerFactory.getLogger(MongoEventLog::class.java) as Logger
        val warnings = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(warnings)
        try {
            // A grace is resolved at the first poll past it, a poll at most a second apart: up to two seconds each.
            val waited = (MongoMissingSequences.UNHELD_WARN * 2 + 2) * MongoEventLog.STALL_GRACE.inWholeMilliseconds
            kotlinx.coroutines.withTimeoutOrNull(waited) { log.tail(0).take(2).toList() }.assert().isNull()

            val warning = warnings.list.map { it.formattedMessage }.single { "no open block holds" in it }
            warning.assert().contains("partition 0 of $logContext").contains("sequence 2,")
                .contains(
                    "{_id: NumberLong(\"${MongoEventLog.positionId(0, 2)}\"), partition: 0, sequence: NumberLong(\"2\")"
                )
                .contains("gap: \"recovered\"")
        } finally {
            logger.detachAppender(warnings)
        }
        // The placeholder the warning names lets the tail on.
        orders.insertOne(
            Document("_id", MongoEventLog.positionId(0, 2)).append("partition", 0).append("sequence", 2L)
                .append("gap", "recovered").append("gap_node", "operator"),
        )
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(2).toList() }.map { it.stream.ref.id }
            .assert().containsExactly("a", "c")
    }

    /** An open block of [node], [from] to [to], as a partition's counter lists one. */
    private fun block(node: String, from: Long, to: Long) = Document("node", node).append("from", from).append("to", to)
        .append("collections", listOf(collection(logContext, order.name)))

    /** A seal's placeholder at [sequence] of partition 0, another node's. */
    private fun sealed(sequence: Long) = Document("_id", MongoEventLog.positionId(0, sequence)).append("partition", 0)
        .append("sequence", sequence).append("gap", MongoEventLog.GAP_SEALED).append("gap_node", "another")

    private companion object {
        const val DUPLICATE_KEY = 11000
        const val WRITE_CONCERN_FAILED = 64

        /** A sequence past every stream of the warning's test. */
        const val BEYOND = 300L
    }
}
