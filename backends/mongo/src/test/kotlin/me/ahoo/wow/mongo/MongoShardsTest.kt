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

import com.mongodb.MongoCommandException
import com.mongodb.MongoNamespace
import com.mongodb.ServerAddress
import com.mongodb.client.FindIterable
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.IndexOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.spi.StoreDdl
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test

/**
 * The shard commands a store sends a mongos (decision D277), against a driver that records them: the real cluster
 * runs them in `MongoShardingTest` (`test/mongo-cluster/cluster.sh test sharded`).
 */
class MongoShardsTest {
    /** The commands the admin database received, by name, and how each is answered. */
    private val commands = mutableListOf<Document>()
    private val refused = mutableSetOf<String>()
    private var shards = listOf("sh1", "sh2")
    private var hello = Document("msg", "isdbgrid")
    private var documents = 0L
    private var chunks = 1L

    private val admin: MongoDatabase = mockk {
        every { withTimeout(any(), any()) } answers { self as MongoDatabase }
        every { runCommand(any<Bson>()) } answers {
            val command = firstArg<Document>()
            commands += command
            val name = command.keys.first()
            if (name in refused) throw MongoCommandException(error(name), ServerAddress())
            when (name) {
                "hello" -> hello
                "listShards" -> Document("shards", shards.map { Document("_id", it) })
                else -> Document("ok", 1)
            }
        }
    }
    private val config: MongoDatabase = mockk {
        every { withTimeout(any(), any()) } answers { self as MongoDatabase }
        every { getCollection("collections") } returns mockk {
            every { find(any<Bson>()) } returns mockk<FindIterable<Document>> {
                every { first() } returns Document("_id", NAMESPACE).append("uuid", "uuid-1")
            }
        }
        every { getCollection("chunks") } returns mockk {
            every { countDocuments(any<Bson>()) } answers { chunks }
        }
    }
    private val client = mockk<MongoClient> {
        every { getDatabase("admin") } returns admin
        every { getDatabase("config") } returns config
    }
    private val database = mockk<MongoDatabase> { every { name } returns "wow" }
    private val index = slot<Bson>()
    private val collection: MongoCollection<Document> = mockk {
        every { withTimeout(any(), any()) } answers { self as MongoCollection<Document> }
        every { namespace } returns MongoNamespace(NAMESPACE)
        every { createIndex(capture(index), any<IndexOptions>()) } returns MongoShards.SHARD_KEY
        every { estimatedDocumentCount() } answers { documents }
    }

    @Test
    fun `a mongos is told by its hello, and sharding auto asks only with ddl auto`() {
        MongoShards.isMongos(client).assert().isTrue()
        MongoSharding.AUTO.shards(client, StoreDdl.AUTO).assert().isTrue()
        hello = Document("isWritablePrimary", true)
        MongoShards.isMongos(client).assert().isFalse()
        MongoSharding.AUTO.shards(client, StoreDdl.AUTO).assert().isFalse()
    }

    @Test
    fun `a new log collection is sharded by partition, split at each and its chunks spread in turn`() {
        MongoShards.shardLog(client, database, collection, 4)
        index.captured.toBsonDocument().assert().isEqualTo(
            BsonDocument("partition", BsonInt32(1)).append("_id", BsonInt32(1))
        )
        commands.first { it.containsKey("shardCollection") }.assert()
            .isEqualTo(Document("shardCollection", NAMESPACE).append("key", Document("partition", 1)))
        commands.filter { it.containsKey("split") }.map { it["middle"] }.assert()
            .containsExactly(Document("partition", 1), Document("partition", 2), Document("partition", 3))
        commands.filter { it.containsKey("moveChunk") }.map { it["find"] to it["to"] }.assert().containsExactly(
            Document("partition", 0) to "sh1",
            Document("partition", 1) to "sh2",
            Document("partition", 2) to "sh1",
            Document("partition", 3) to "sh2",
        )
    }

    @Test
    fun `a snapshot collection is sharded by tenant and the aggregate id's hash, and left to the balancer`() {
        MongoShards.shardSnapshots(client, database, collection)
        index.captured.toBsonDocument().assert()
            .isEqualTo(BsonDocument("tenant_id", BsonInt32(1)).append("aggregate_id", BsonString("hashed")))
        commands.single { it.containsKey("shardCollection") }["key"].assert()
            .isEqualTo(Document("tenant_id", 1).append("aggregate_id", "hashed"))
        commands.filter { it.containsKey("split") || it.containsKey("moveChunk") }.assert().isEmpty()
    }

    @Test
    fun `a collection with documents, chunks already or one shard is not spread again`() {
        documents = 5
        MongoShards.shardLog(client, database, collection, 4)
        documents = 0
        chunks = 4
        MongoShards.shardLog(client, database, collection, 4)
        chunks = 1
        shards = listOf("sh1")
        MongoShards.shardLog(client, database, collection, 4)
        commands.filter { it.containsKey("split") || it.containsKey("moveChunk") }.assert().isEmpty()
        verify(exactly = 3) { admin.runCommand(match<Document> { it.containsKey("shardCollection") }) }
    }

    @Test
    fun `a split or a move refused is left to the balancer, the others still run`() {
        refused += "split"
        MongoShards.shardLog(client, database, collection, 2)
        commands.count { it.containsKey("moveChunk") }.assert().isEqualTo(2)
    }

    @Test
    fun `a collection sharded by another key is refused`() {
        refused += "shardCollection"
        assertThrownBy<IllegalStateException> { MongoShards.shardLog(client, database, collection, 2) }
            .message().contains("cannot be sharded by")
    }

    private fun error(name: String) = BsonDocument("ok", BsonInt32(0))
        .append("code", BsonInt32(ALREADY_INITIALIZED))
        .append("errmsg", BsonString("$name refused"))

    private companion object {
        const val NAMESPACE = "wow.wow_log__shop__order"
        const val ALREADY_INITIALIZED = 23
    }
}
