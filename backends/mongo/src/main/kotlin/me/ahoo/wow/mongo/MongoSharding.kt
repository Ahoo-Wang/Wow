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
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.StoreDdl
import org.bson.Document
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory

/**
 * Whether a store shards the collections it creates on a sharded cluster (decision D277): [AUTO] when it is connected
 * to a mongos and creates its schema ([StoreDdl.AUTO]), [OFF] for a cluster whose operators shard it themselves. The
 * option `sharding` of a MongoDB store (`wow.mongo.sharding`), `auto` or `off`.
 */
@InternalWowApi
enum class MongoSharding {
    AUTO,
    OFF;

    /** Whether a store over [client] with [ddl] shards its collections: asks the server only when it may. */
    fun shards(client: MongoClient, ddl: StoreDdl): Boolean =
        this == AUTO && ddl == StoreDdl.AUTO && MongoShards.isMongos(client)

    @InternalWowApi
    companion object {
        /** The value of the store option, case-insensitive; [AUTO] when not set. */
        @JvmStatic
        fun of(option: String?): MongoSharding = option?.let { value ->
            requireNotNull(entries.firstOrNull { it.name.equals(value, ignoreCase = true) }) {
                "The option sharding is auto or off, not $value."
            }
        } ?: AUTO
    }
}

/**
 * Shards a store's collections (decision D277). Each step is idempotent, so nodes creating one collection at once
 * agree: `shardCollection` of a collection sharded by the same key is a no-op. A collection already sharded by
 * another key is refused, as a layout this version does not know.
 */
internal object MongoShards {
    private val log = LoggerFactory.getLogger(MongoShards::class.java)

    /** The index each sharded collection keeps for its shard key. */
    const val SHARD_KEY = "shard_key"

    /** Whether [client] talks to a mongos: the server a sharded cluster's clients connect to. */
    fun isMongos(client: MongoClient): Boolean =
        MongoTimeouts.ddl(client.getDatabase(ADMIN)).runCommand(Document("hello", 1)).getString("msg") == MONGOS

    /**
     * Shards the log collection [collection] of [partitions] partitions by partition (decision D277): its entries of
     * one partition are one range, one chunk each, spread over the shards while the collection is new. The shard
     * key's index is `(partition, _id)`: the log's unique keys are partial, and no partial index serves a shard key.
     */
    fun shardLog(client: MongoClient, database: MongoDatabase, collection: MongoCollection<Document>, partitions: Int) =
        shard(
            client,
            database,
            collection,
            Document(PARTITION, 1),
            Indexes.ascending(PARTITION, ID),
            (0 until partitions).map { Document(PARTITION, it) },
        )

    /**
     * Shards the timer collection [collection] of a log of [partitions] partitions by partition (decision D308), as the
     * log is: a timer is in its owner's partition, so the reads and writes by owner, and a node's due read, reach the
     * shards of their partitions; the timers' unique key leads with the partition. The shard key's index is
     * `(partition, _id)`, as the log's.
     */
    fun shardTimers(
        client: MongoClient,
        database: MongoDatabase,
        collection: MongoCollection<Document>,
        partitions: Int
    ) =
        shardLog(client, database, collection, partitions)

    /**
     * Shards the snapshot collection [collection] by tenant, then the aggregate id's hash (decision D277): new
     * aggregates spread over the shards, a load reads one, a query of one tenant that tenant's chunks.
     */
    fun shardSnapshots(client: MongoClient, database: MongoDatabase, collection: MongoCollection<Document>) =
        shard(
            client,
            database,
            collection,
            Document(TENANT_ID, 1).append(AGGREGATE_ID, HASHED),
            Indexes.compoundIndex(Indexes.ascending(TENANT_ID), Indexes.hashed(AGGREGATE_ID)),
        )

    /**
     * Shards [collection] by [key], whose index [index] it creates first; when [chunks] are given and the collection
     * is new (empty, one chunk), splits it at each of them and spreads the chunks over the shards in turn.
     */
    fun shard(
        client: MongoClient,
        database: MongoDatabase,
        collection: MongoCollection<Document>,
        key: Document,
        index: Bson,
        chunks: List<Document> = emptyList(),
    ) {
        MongoFormat.index(collection, index, IndexOptions().name(SHARD_KEY), StoreDdl.AUTO)
        val namespace = collection.namespace.fullName
        val admin = MongoTimeouts.ddl(client.getDatabase(ADMIN))
        try {
            admin.runCommand(Document("shardCollection", namespace).append("key", key))
        } catch (failure: MongoCommandException) {
            throw IllegalStateException(
                "$namespace cannot be sharded by $key, the key this version of Wow shards it by: ${failure.errorMessage}",
                failure,
            )
        }
        if (chunks.isNotEmpty() && collection.estimatedDocumentCount() == 0L && chunksOf(client, namespace) == 1L) {
            spread(admin, database, namespace, chunks)
        }
    }

    /** The chunks of [namespace], from the cluster's config database. */
    private fun chunksOf(client: MongoClient, namespace: String): Long {
        val config = MongoTimeouts.ddl(client.getDatabase(CONFIG))
        val uuid = config.getCollection(COLLECTIONS).find(Filters.eq(ID, namespace)).first()?.get(UUID) ?: return 0
        return config.getCollection(CHUNKS).countDocuments(Filters.eq(UUID, uuid))
    }

    /**
     * Splits [namespace] at [chunks], each the lower bound of one, and moves the n-th chunk to the n-th shard, in
     * turn. Best effort: a node doing the same at once, or the balancer, may have moved a chunk already, and the
     * balancer spreads what this leaves.
     */
    private fun spread(admin: MongoDatabase, database: MongoDatabase, namespace: String, chunks: List<Document>) {
        val shards = admin.runCommand(Document("listShards", 1)).getList("shards", Document::class.java)
            .map { it.getString(ID) }
        if (shards.size < 2) return
        chunks.drop(1).forEach { bound ->
            tryCommand(admin, database, Document("split", namespace).append("middle", bound))
        }
        chunks.forEachIndexed { index, bound ->
            val target = shards[index % shards.size]
            tryCommand(admin, database, Document("moveChunk", namespace).append("find", bound).append("to", target))
        }
    }

    private fun tryCommand(admin: MongoDatabase, database: MongoDatabase, command: Document) {
        try {
            admin.runCommand(command)
        } catch (failure: MongoCommandException) {
            log.debug("{} in {} left to the balancer: {}", command.toJson(), database.name, failure.errorMessage)
        }
    }

    private const val PARTITION = "partition"
    private const val TENANT_ID = "tenant_id"
    private const val AGGREGATE_ID = "aggregate_id"
    private const val HASHED = "hashed"
    private const val ADMIN = "admin"
    private const val CONFIG = "config"
    private const val COLLECTIONS = "collections"
    private const val CHUNKS = "chunks"
    private const val ID = "_id"
    private const val UUID = "uuid"
    private const val MONGOS = "isdbgrid"
}
