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

import com.mongodb.MongoWriteException
import com.mongodb.ReadConcern
import com.mongodb.WriteConcern
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.EnsuredCollections
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.MongoShards
import me.ahoo.wow.mongo.isDuplicateKey
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.AGGREGATE
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.AGGREGATE_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.CONTEXT
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.FORMAT
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.PARTITION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.PREFIX
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.REQUEST_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.REQUEST_KEY
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.TENANT_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.VERSION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.VERSION_KEY
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.Ddl
import org.bson.BsonType
import org.bson.Document
import java.util.concurrent.TimeUnit

/** The log collection of one aggregate type. */
internal class AggregateCollection(
    val aggregate: String,
    val name: String,
    database: MongoDatabase,
    appendMillis: Long,
) {
    val log: MongoCollection<Document> = database.getCollection(name)

    /** The entries a majority has committed: every read (finding A2-2). */
    val committed: MongoCollection<Document> = log.withReadConcern(ReadConcern.MAJORITY)

    /** The appends and placeholders, acknowledged by a majority within the append timeout. */
    val appends: MongoCollection<Document> =
        log.withWriteConcern(WriteConcern.MAJORITY).withTimeout(appendMillis, TimeUnit.MILLISECONDS)
}

/**
 * The aggregate collections of one context's log on MongoDB and their registry ([MongoEventLog.REGISTRY]): each
 * collection is made sure of (format claimed, indexes created, sharded on a sharded cluster, registered) once, on its
 * first use, and the log reads across the ones it knows.
 */
internal class MongoLogCollections(
    private val client: MongoClient,
    private val database: MongoDatabase,
    private val registry: MongoCollection<Document>,
    private val context: String,
    private val partitions: Int,
    private val countersName: String,
    private val ddl: Ddl,
    private val sharded: () -> Boolean,
    private val appendMillis: Long,
) {
    /** The aggregate collections whose creation and registration this log has made sure of, each once. */
    private val ensured = EnsuredCollections<AggregateCollection>()

    /** The aggregate collections of the context, as of the last read of the registry, in registry order. */
    @Volatile
    private var registered: List<AggregateCollection>? = null

    fun of(type: AggregateType): AggregateCollection {
        require(type.context == context) { "$type is not in context $context." }
        return of(type.name)
    }

    /** The collection of [aggregate], created and registered when this log has not yet made sure it is. */
    fun of(aggregate: String): AggregateCollection = ensured.get(aggregate) { ensure(aggregate) }

    /**
     * Claims the format of [aggregate]'s collection, creates its indexes and records it in the registry. Each step is
     * idempotent, so nodes creating one collection at once agree; two aggregates that take one name are refused.
     */
    private fun ensure(aggregate: String): AggregateCollection {
        val collection =
            AggregateCollection(aggregate, MongoNames.of(PREFIX, context, aggregate), database, appendMillis)
        require(collection.name != countersName) {
            "The aggregate $aggregate of $context would take the name of the log's partition counters."
        }
        MongoFormat.claim(database, collection.name, FORMAT, ddl)
        val log = collection.log
        // An entry's position is its `_id` (roadmap §6 item 17): a log an alpha wrote has the driver's ObjectIds, and
        // its tail would read nothing of them.
        check(log.find(Filters.type(ID, BsonType.OBJECT_ID)).projection(Projections.include(ID)).first() == null) {
            "${collection.name} was written by an earlier 10.0 alpha, whose entries have no position as their _id: " +
                "rebuild the log, an alpha's data is not migrated."
        }
        // Placeholders have no version and no request: the two keys cover the streams alone. Both lead with the
        // partition, so they stay unique on a collection sharded by it (decision D277).
        MongoFormat.index(
            log,
            Indexes.ascending(PARTITION, AGGREGATE_ID, TENANT_ID, VERSION),
            streams(VERSION_KEY, VERSION),
            ddl,
        )
        MongoFormat.index(
            log,
            Indexes.ascending(PARTITION, TENANT_ID, REQUEST_ID),
            streams(REQUEST_KEY, REQUEST_ID),
            ddl
        )
        if (sharded()) {
            // Decision D277: by partition, one chunk each, spread over the shards while the collection is new.
            MongoShards.shardLog(client, database, log, partitions)
        }
        if (ddl != Ddl.AUTO) {
            // The registry is the schema's: a validated or foreign schema registered its collections already.
            return collection
        }
        try {
            registry.insertOne(Document(ID, collection.name).append(CONTEXT, context).append(AGGREGATE, aggregate))
        } catch (failure: MongoWriteException) {
            if (!failure.isDuplicateKey()) {
                throw failure
            }
        }
        val holder = registry.find(Filters.eq(ID, collection.name)).first()!!
        check(holder.getString(CONTEXT) == context && holder.getString(AGGREGATE) == aggregate) {
            "The aggregate $aggregate of $context takes the log collection ${collection.name}, " +
                "which the aggregate ${holder.getString(AGGREGATE)} of ${holder.getString(CONTEXT)} has."
        }
        return collection
    }

    private fun unique(name: String): IndexOptions = IndexOptions().unique(true).name(name)

    /** A unique index of the documents that have [field], the streams'. */
    private fun streams(name: String, field: String): IndexOptions =
        unique(name).partialFilterExpression(Filters.exists(field))

    /** The aggregate collections this log knows: the registry as last read, and the ones it made sure of since. */
    fun known(): List<AggregateCollection> =
        ((registered ?: register()) + ensured.values).distinctBy { it.name }

    /** The aggregate collection named [name], reading the registry again when this log does not know it. */
    fun named(name: String): AggregateCollection =
        known().firstOrNull { it.name == name } ?: register().first { it.name == name }

    /** Reads the registry again: the aggregate collections of the context, in registry order. */
    fun register(): List<AggregateCollection> =
        registry.find(Filters.eq(CONTEXT, context)).sort(Sorts.ascending(AGGREGATE))
            .map { AggregateCollection(it.getString(AGGREGATE), it.getString(ID), database, appendMillis) }
            .toList()
            .also { registered = it }
}
