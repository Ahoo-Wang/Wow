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

import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoException
import com.mongodb.ServerAddress
import com.mongodb.bulk.BulkWriteError
import com.mongodb.bulk.BulkWriteResult
import com.mongodb.client.MongoClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.mongo.MongoDatabases
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.spi.Ddl
import org.bson.BsonDocument
import org.bson.Document
import org.junit.jupiter.api.Test

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

        placeholders.close(closing)

        verify(exactly = 1) { sequencer.settle(closing) }
    }

    @Test
    fun `a closing that meets its own earlier range is settled`() {
        val (placeholders, collection) = placeholdersOn()
        collection.log.insertOne(
            placeholder(2, MongoEventLog.GAP_CLOSED).append(MongoEventLog.GAP_TO, MongoEventLog.BLOCK)
        )

        placeholders.close(closing)

        verify(exactly = 1) { sequencer.settle(closing) }
    }

    @Test
    fun `a closing that meets part of its range held is not settled`() {
        val (placeholders, collection) = placeholdersOn()
        collection.log.insertMany((2L..PART).map { placeholder(it) })

        placeholders.close(closing)

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

        placeholders.close(closing)

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

        placeholders.fill(collection, 0, SEQUENCE, MongoEventLog.GAP_UNKNOWN)

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

    private companion object {
        const val ORDER = "order"
        const val APPEND_MILLIS = 5_000L
        const val PART = 100L
        const val SEQUENCE = 7L
        const val DOCUMENT_VALIDATION_FAILURE = 121
    }
}
