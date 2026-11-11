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

import com.mongodb.client.model.Filters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.CountedMongo
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.spi.log.AppendOutcome
import org.bson.Document
import org.junit.jupiter.api.Test

/** The aggregate collections a log makes sure of: only those of its own context. */
class MongoLogCollectionsTest : MongoLogFixtures() {
    @Test
    fun `an aggregate type of another context is refused by a read, an append and the collection checks`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val log = MongoLogFixture.log(1, context)
            val elsewhere = AggregateType(MongoLogFixture.newContext(), "order")
            val first = stream(elsewhere.ref("tenant", "a"))
            val refused = "$elsewhere is not in context $context."
            // A read makes sure of the type's collection; an append and a group refuse the stream first.
            assertThrownBy<IllegalArgumentException> { runBlocking { log.read(first.ref, 1, 1).toList() } }
                .hasMessage(refused)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.readTail(first.ref, 1, 0) } }
                .hasMessage(refused)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.append(first, null) } }
                .hasMessage("${first.ref} is not in context $context.")
            val group = listOf(first to null, stream(elsewhere.ref("tenant", "b")) to null)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.appendAll(group) } }
                .hasMessage("A group commit appends streams of $context.")
            // The checks of a type's collection, which create none.
            assertThrownBy<IllegalArgumentException> { runBlocking { log.holds(elsewhere) } }.hasMessage(refused)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.aggregates(elsewhere, null, 1) } }
                .hasMessage(refused)
        }

    @Test
    fun `creating the schema twice is harmless`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context, listOf("order"))
        MongoLogFixture.log(4, context, listOf("order")).partitions.assert().isEqualTo(4)
    }

    @Test
    fun `a log recorded in another format version is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context)
        MongoLogFixture.bumpFormat(collection(context, "partition"))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context) }
    }

    @Test
    fun `an aggregate collection recorded in another format version is refused`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context, listOf("order"))
        MongoLogFixture.bumpFormat(collection(context, "order"))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context, listOf("order")) }
        val log = MongoLogFixture.log(4, context)
        assertThrownBy<IllegalStateException> {
            runBlocking { log.read(AggregateType(context, "order").ref("tenant", "a")).toList() }
        }
    }

    @Test
    fun `a log collection without a format record is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.createUnrecorded(collection(context, "partition"))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context) }
    }

    @Test
    fun `an aggregate named like the partition counters is refused`() {
        val context = MongoLogFixture.newContext()
        assertThrownBy<IllegalArgumentException> { MongoLogFixture.log(4, context, listOf("Partition")) }
    }

    @Test
    fun `two aggregates that take one collection name are refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context, listOf("order-item"))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context, listOf("order_item")) }
    }

    @Test
    fun `a long aggregate name is cut and hashed as PostgreSQL's identifiers are`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val long = AggregateType(context, "A".repeat(80))
        val log = MongoLogFixture.log(1, context)
        log.append(stream("a", 1, aggregate = long)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.append(stream("a", 1, aggregate = long)).assert().isEqualTo(AppendOutcome.Conflict(1))
        val name = MongoNames.of("wow_log", context, long.name)
        name.length.assert().isEqualTo(MongoNames.MAX_LENGTH)
        name.assert().startsWith(collection(context, "a").take(MongoNames.MAX_LENGTH - 9))
        MongoNames.limited(collection(context, "a".repeat(80)) + "x")
            .assert().isNotEqualTo(MongoNames.limited(collection(context, "a".repeat(80)) + "y"))
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).listCollectionNames().toList()
            .assert().contains(name)
    }

    @Test
    fun `nodes creating a new log at once all succeed`(): Unit = runBlocking {
        // Finding A2-8: the counters were inserted when none were found, and the later node hit a duplicate key.
        // A node that came second also used to find the first one's counters half written and refuse the log.
        repeat(RACES) {
            val context = MongoLogFixture.newContext()
            coroutineScope {
                (1..8).map { async(Dispatchers.IO) { MongoLogFixture.log(64, context).partitions } }.awaitAll()
            }.forEach { it.assert().isEqualTo(64) }
            MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection(context, "partition"))
                .countDocuments().assert().isEqualTo(64L)
            assertThrownBy<IllegalStateException> { MongoLogFixture.log(32, context) }
            MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection(context, "partition"))
                .countDocuments().assert().isEqualTo(64L)
        }
    }

    @Test
    fun `an entry's _id is its position, a placeholder's too, and the position has no index of its own`(): Unit =
        runBlocking {
            // Roadmap §6 item 17: `partition << 51 | sequence`, so a partition's entries are one range of _id. One
            // partition, so the second request meets the first.
            val context = MongoLogFixture.newContext()
            val log = MongoLogFixture.log(1, context)
            val order = AggregateType(context, "order")
            val committed =
                log.append(stream("a", 1, requestId = "taken", aggregate = order)) as AppendOutcome.Committed
            // A duplicate request fails at the insert: a placeholder takes its sequence.
            log.append(stream("b", 1, requestId = "taken", aggregate = order))
                .assert().isInstanceOf(AppendOutcome.AlreadyCommitted::class.java)
            val documents = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
                .getCollection(collection(context, "order"))
            documents.find().toList().forEach { document ->
                document.getLong("_id").assert()
                    .isEqualTo(MongoEventLog.positionId(document.getInteger("partition"), document.getLong("sequence")))
            }
            val position = MongoEventLog.positionId(committed.position.partition, committed.position.sequence)
            documents.find(Filters.eq("_id", position))
                .first()!!.getString("request_id").assert().isEqualTo("taken")
            documents.find(Filters.exists(MongoEventLog.GAP)).toList().assert().isNotEmpty()
            documents.listIndexes().map { it.getString("name") }.toList()
                .assert().containsExactlyInAnyOrderElementsOf(
                    listOf("_id_", MongoEventLog.VERSION_KEY, MongoEventLog.REQUEST_KEY) + MongoLogFixture.shardIndexes,
                )
            // Decision D277: both keys lead with the partition, so they stay unique on a collection sharded by it.
            documents.listIndexes().filter { it.getString("name") in setOf(MongoEventLog.VERSION_KEY, MongoEventLog.REQUEST_KEY) }
                .map { (it["key"] as Document).keys.first() }.toList()
                .assert().containsExactly("partition", "partition")
        }

    @Test
    fun `a log an alpha wrote, its entries under ObjectIds, is refused rather than read without them`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val order = AggregateType(context, "order")
            MongoLogFixture.log(4, context).append(stream("a", 1, aggregate = order))
            // What an alpha left: an entry whose _id the driver gave it.
            MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection(context, "order"))
                .insertOne(Document("partition", 0).append("sequence", 1L).append("version", 1))
            val restarted = MongoLogFixture.log(4, context)
            assertThrownBy<IllegalStateException> {
                runBlocking { restarted.append(stream("b", 1, aggregate = order)) }
            }.hasMessageContaining("earlier 10.0 alpha")
        }

    @Test
    fun `a different partition count is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context)
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(8, context) }
    }

    @Test
    fun `a new aggregate type's first appends at once make sure of its collection once`(): Unit = runBlocking {
        // A node's first commands of a type all touch its collection at once: one of them creates it, the others wait.
        val context = MongoLogFixture.newContext()
        val type = AggregateType(context, "order")
        val log = MongoEventLog(CountedMongo.client, MongoLogFixture.DATABASE, context, 4).also { it.createSchema() }
        val (outcomes, sent) = CountedMongo.commandsOf {
            runBlocking {
                (1..FIRST_TOUCHES)
                    .map { async(Dispatchers.Default) { log.append(stream("o-$it", 1, aggregate = type)) } }
                    .awaitAll()
            }
        }
        outcomes.assert().allMatch { it is AppendOutcome.Committed }
        // Its two indexes, and on a sharded cluster the shard key's, which sharding the collection creates.
        sent.count { it.name == "createIndexes" }.assert().isEqualTo(2 + MongoLogFixture.shardIndexes.size)
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
            .getCollection(MongoNames.of("wow_log", context, "order"))
            .listIndexes().mapNotNull { it.getString("name") }.toList()
            .assert().containsAll(MongoLogFixture.shardIndexes)
    }

    private companion object {
        const val RACES = 5
        const val FIRST_TOUCHES = 100
    }
}
