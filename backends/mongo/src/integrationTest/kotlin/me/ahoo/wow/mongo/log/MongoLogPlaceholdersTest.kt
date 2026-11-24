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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoException
import com.mongodb.ServerAddress
import com.mongodb.bulk.BulkWriteError
import com.mongodb.bulk.BulkWriteResult
import com.mongodb.client.MongoClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.mongo.MongoDatabases
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.spi.Ddl
import org.bson.BsonDocument
import org.bson.Document
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * What [MongoLogPlaceholders] takes as settled (#6745), against a real collection and a sequencer it reports to: its
 * own write acknowledged by a majority, or what a majority read finds after a duplicate key; nothing a read that fails
 * could not confirm.
 */
class MongoLogPlaceholdersTest {
    private val context = MongoLogFixture.newContext("placeholders")
    private val sequencer = mockk<MongoSequencer>(relaxed = true).also { every { it.node } returns "this-node" }

    /** The placeholders of [context]'s log over [client], and its `order` collection. */
    private fun placeholdersOn(
        client: MongoClient = MongoLogFixture.client
    ): Pair<MongoLogPlaceholders, AggregateCollection> {
        // The log's schema first, as a log makes it: the registry's format claimed before a collection registers.
        MongoLogFixture.log(1, context)
        val database = MongoDatabases.of(client, MongoLogFixture.DATABASE)
        val collections = MongoLogCollections(
            client,
            database,
            database.getCollection(MongoEventLog.REGISTRY),
            context,
            1,
            MongoNames.of("wow_log", context, "partition"),
            Ddl.AUTO,
            false,
            APPEND_MILLIS,
        )
        return MongoLogPlaceholders(sequencer, collections) to collections.of(ORDER)
    }

    private fun placeholder(sequence: Long, reason: String = MongoEventLog.GAP_SEALED) =
        Document("_id", MongoEventLog.positionId(0, sequence)).append("partition", 0).append("sequence", sequence)
            .append("gap", reason).append("gap_node", "another")

    private val closing = MongoSequencer.Closing(0, 2, MongoEventLog.BLOCK, MongoNames.of("wow_log", context, ORDER))

    @Test
    fun `a closing that meets a seal holding its whole range is settled`() {
        val (placeholders, collection) = placeholdersOn()
        collection.log.insertMany((2L..MongoEventLog.BLOCK).map { placeholder(it) })

        placeholders.close(closing, deadline = null)

        verify(exactly = 1) { sequencer.settle(closing) }
    }

    @Test
    fun `a closing that meets its own earlier range is settled`() {
        val (placeholders, collection) = placeholdersOn()
        collection.log.insertOne(
            placeholder(2, MongoEventLog.GAP_CLOSED).append(MongoEventLog.GAP_TO, MongoEventLog.BLOCK)
        )

        placeholders.close(closing, deadline = null)

        verify(exactly = 1) { sequencer.settle(closing) }
    }

    @Test
    fun `a closing that meets part of its range held is not settled`() {
        val (placeholders, collection) = placeholdersOn()
        collection.log.insertMany((2L..PART).map { placeholder(it) })

        placeholders.close(closing, deadline = null)

        verify(exactly = 0) { sequencer.settle(any<MongoSequencer.Closing>()) }
    }

    @Test
    fun `a closing whose confirming read fails is not settled`() {
        placeholdersOn().second.log.insertMany((2L..MongoEventLog.BLOCK).map { placeholder(it) })
        val (placeholders, _) = placeholdersOn(
            faulty(
                find = { filter ->
                    if ("\$gte" in filter.toBsonDocument().toJson()) {
                        throw MongoException(
                            "read failed"
                        )
                    }
                }
            ),
        )

        placeholders.close(closing, deadline = null)

        verify(exactly = 0) { sequencer.settle(any<MongoSequencer.Closing>()) }
    }

    @Test
    fun `a placeholder whose confirming read fails is left to the tail`() {
        placeholdersOn().second.log.insertOne(placeholder(SEQUENCE))
        val (placeholders, collection) = placeholdersOn(
            faulty(
                find = { filter -> if ("\$in" in filter.toBsonDocument().toJson()) throw MongoException("read failed") }
            ),
        )

        placeholders.fill(collection, 0, SEQUENCE, MongoEventLog.GAP_UNKNOWN, deadline = null)

        verify(exactly = 1) { sequencer.unfilled(0, SEQUENCE, collection.name) }
        verify(exactly = 0) { sequencer.settle(0, SEQUENCE) }
    }

    @Test
    fun `a seal the store refuses for another reason than a taken position is thrown`() {
        val refused = MongoBulkWriteException(
            BulkWriteResult.acknowledged(0, 0, 0, 0, emptyList(), emptyList()),
            listOf(BulkWriteError(DOCUMENT_VALIDATION_FAILURE, "Document failed validation", BsonDocument(), 0)),
            null,
            ServerAddress(),
            emptySet(),
        )
        val (placeholders, _) = placeholdersOn(faulty(many = { _, _ -> throw refused }))
        val block = MongoSequencer.OpenBlock(
            "another",
            2,
            MongoEventLog.BLOCK,
            listOf(MongoNames.of("wow_log", context, ORDER))
        )

        assertThrownBy<MongoBulkWriteException> { placeholders.seal(0, 2, block) }

        verify(exactly = 0) { sequencer.remove(any(), any()) }
    }

    @Test
    fun `a placeholder that fails with what is not MongoDB's is warned of, one MongoDB or a closed client fails is not`() {
        val defect = IllegalStateException("a defect")
        var failure: RuntimeException? = null
        val (placeholders, collection) = placeholdersOn(
            faulty(insert = { _, write -> failure?.let { throw it } ?: write() }),
        )
        val failures = listOf(defect, MongoException("write failed"), IllegalStateException(CLIENT_CLOSED))

        val logged = warningsOf(collection) {
            failures.forEachIndexed { at, failing ->
                failure = failing
                placeholders.fill(collection, 0, SEQUENCE + at, MongoEventLog.GAP_UNKNOWN, deadline = null)
            }
        }

        // Each is left to the tail, which fills it again; only the defect is warned of, with its exception.
        failures.indices.forEach { at -> verify(exactly = 1) { sequencer.unfilled(0, SEQUENCE + at, collection.name) } }
        verify(exactly = 0) { sequencer.settle(any<Int>(), any<Long>()) }
        val warning = logged.single()
        warning.formattedMessage.assert()
            .startsWith("The placeholder at partition 0, sequence $SEQUENCE in ${collection.name} failed")
        warning.throwableProxy.message.assert().isEqualTo(defect.message)
    }

    @Test
    fun `placeholders, and the majority read after a duplicate, that fail with what is not MongoDB's are warned of`() {
        placeholdersOn().second.log.insertOne(placeholder(SEQUENCE))
        val defect = IllegalStateException("a defect")
        var failingInsert: RuntimeException? = null
        var failingRead: RuntimeException? = null
        val (placeholders, collection) = placeholdersOn(
            faulty(
                many = { documents, insert -> failingInsert?.let { throw it } ?: insert(documents) },
                find = { filter -> if ("\$in" in filter.toBsonDocument().toJson()) failingRead?.let { throw it } },
            ),
        )
        fun gaps(vararg sequences: Long) = sequences.map { MongoLogPlaceholders.Gap(0, it, MongoEventLog.GAP_UNKNOWN) }

        val logged = warningsOf(collection) {
            failingInsert = defect
            placeholders.fillAll(collection, gaps(SEQUENCE, SEQUENCE + 1), deadline = null)
            // The second lands; the first meets the placeholder already there, which the read cannot confirm.
            failingInsert = null
            failingRead = defect
            placeholders.fillAll(collection, gaps(SEQUENCE, SEQUENCE + 2), deadline = null)
            // MongoDB's own failure of that read is not warned of.
            failingRead = MongoException("read failed")
            placeholders.fillAll(collection, gaps(SEQUENCE, SEQUENCE + 3), deadline = null)
        }

        verify(exactly = 3) { sequencer.unfilled(0, SEQUENCE, collection.name) }
        verify(exactly = 1) { sequencer.unfilled(0, SEQUENCE + 1, collection.name) }
        verify(exactly = 1) { sequencer.settle(0, SEQUENCE + 2) }
        verify(exactly = 1) { sequencer.settle(0, SEQUENCE + 3) }
        logged.map { it.formattedMessage.substringBefore(" failed") }.assert().containsExactly(
            "The placeholders at partition 0, sequences $SEQUENCE, ${SEQUENCE + 1} in ${collection.name}",
            "The majority read of partition 0, sequence $SEQUENCE in ${collection.name}",
        )
        logged.map { it.throwableProxy.message }.assert().containsOnly(defect.message)
    }

    /** The lines [block] logs at WARN in the log's category that name [collection]. */
    private fun warningsOf(collection: AggregateCollection, block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(MongoEventLog::class.java) as Logger
        val lines = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(lines)
        try {
            block()
        } finally {
            logger.detachAppender(lines)
        }
        return lines.list.filter { it.level == Level.WARN && collection.name in it.formattedMessage }
    }

    private companion object {
        /** The message of the driver's `IllegalStateException` on a call to a closed client. */
        const val CLIENT_CLOSED = "state should be: open"
        const val ORDER = "order"
        const val APPEND_MILLIS = 5_000L
        const val PART = 100L
        const val SEQUENCE = 7L
        const val DOCUMENT_VALIDATION_FAILURE = 121
    }
}
