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

import com.mongodb.MongoException
import com.mongodb.ReadConcern
import com.mongodb.WriteConcern
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.MongoFailures
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.MongoShards
import me.ahoo.wow.mongo.isDuplicateKey
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.ACCESS_WALK
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
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.schema.EnsuredOnce
import org.bson.Document
import org.bson.conversions.Bson
import java.util.concurrent.TimeUnit

/**
 * The log collection [log] as every reader of the log reads it, the `events` queries among them: what a majority has
 * committed (`readConcern: majority`), so nothing that reads the log sees an entry a failover rolls back. The
 * snapshots' queries read at the client's read concern instead: a snapshot is folded from committed entries, so the
 * worst a failover shows of one is an older state, never an entry that was not.
 */
internal fun committed(log: MongoCollection<Document>): MongoCollection<Document> =
    log.withReadConcern(ReadConcern.MAJORITY)

/** The log collection of one aggregate type, and the claims of its creations ([MongoCreationClaims]). */
internal class AggregateCollection(
    val aggregate: String,
    val name: String,
    database: MongoDatabase,
    appendMillis: Long,
    context: String,
) {
    val log: MongoCollection<Document> = database.getCollection(name)

    /** The entries a majority has committed: every read. */
    val committed: MongoCollection<Document> = committed(log)

    /** The appends and placeholders, acknowledged by a majority within the append timeout. */
    val appends: MongoCollection<Document> =
        log.withWriteConcern(WriteConcern.MAJORITY).withTimeout(appendMillis, TimeUnit.MILLISECONDS)

    /** The name of the claims of the type's creations: `wow_claim__<context>__<aggregate>`. */
    val claimsName: String = MongoNames.of(CLAIMS_PREFIX, context, aggregate)

    /**
     * The claims of the type's creations, written as its appends are, by a majority within the append timeout: a
     * claim is as durable as the streams it keeps to one aggregate.
     */
    val claims: MongoCollection<Document> =
        database.getCollection(claimsName).withWriteConcern(WriteConcern.MAJORITY)
            .withTimeout(appendMillis, TimeUnit.MILLISECONDS)

    private companion object {
        const val CLAIMS_PREFIX = "wow_claim"
    }
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
    private val sharded: Boolean,
    private val appendMillis: Long,
) {
    /** The aggregate collections whose creation and registration this log has made sure of, each once. */
    private val ensured = EnsuredOnce<String, AggregateCollection>()

    /** The access schema the node serves for each aggregate, recorded as its walk when its collection is registered. */
    @Volatile
    var served: Map<String, String> = emptyMap()

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
     * The collection of [type] when it exists: one this log made sure of, or one the registry lists, read again when
     * this log does not know it; null before its first append. It creates nothing.
     */
    fun existing(type: AggregateType): AggregateCollection? {
        require(type.context == context) { "$type is not in context $context." }
        return known().firstOrNull { it.aggregate == type.name } ?: register().firstOrNull { it.aggregate == type.name }
    }

    /** The registry document of [type]'s collection, which holds its access walk: none before its first append. */
    fun registryOf(type: AggregateType): Bson = Filters.and(
        Filters.eq(ID, MongoNames.of(PREFIX, context, type.name)),
        Filters.eq(CONTEXT, context),
        Filters.eq(AGGREGATE, type.name),
    )

    /**
     * Claims the format of [aggregate]'s collection, creates its indexes and records it in the registry. Each step is
     * idempotent, so nodes creating one collection at once agree; two aggregates that take one name are refused.
     */
    private fun ensure(aggregate: String): AggregateCollection {
        val collection =
            AggregateCollection(aggregate, MongoNames.of(PREFIX, context, aggregate), database, appendMillis, context)
        require(collection.name != countersName) {
            "The aggregate $aggregate of $context would take the name of the log's partition counters."
        }
        MongoFormat.claim(database, collection.name, FORMAT, ddl)
        val log = collection.log
        // Placeholders have no version and no request: the two keys cover the streams alone. Both lead with the
        // partition, so they stay unique on a collection sharded by it.
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
        // The claims: the `_id`'s index alone, created with the collection by the first claim, after its record.
        MongoFormat.claim(database, collection.claimsName, FORMAT, ddl)
        if (sharded) {
            // Sharded by partition, one chunk each, spread over the shards while the collection is new; the claims by
            // the request.
            MongoShards.shardLog(client, database, log, partitions)
            MongoShards.shardClaims(client, database, collection.claims)
        }
        record(collection, aggregate)
        return collection
    }

    /**
     * Records [collection], [aggregate]'s, in the registry, under every `ddl` (#6768): the record is data, not schema,
     * and a tail, `head` and `heads` read the collections it lists, beside those this node made sure of since it
     * started. A collection that exists without one (a database restored without the registry's documents, collections
     * made by hand) would take appends that no other node's tail reads, nor its own after a restart.
     *
     * A collection that does not exist yet is recorded too, unlike a table on PostgreSQL: MongoDB reads a missing
     * collection as empty, so its record fails no read, and under `ddl: none` the first append creates it. The record
     * is read first and written only when missing ([insert]), as the timer store's is: MongoDB checks an account's
     * privilege before the key, so an insert of a record already there by an account that may only read the registry
     * is refused as unauthorized, not answered as a duplicate (#7230). Only a creation records the collection's access
     * walk as done: a collection recorded after it existed may hold streams a walk has to stamp.
     */
    private fun record(collection: AggregateCollection, aggregate: String) {
        val holder = holderOf(collection.name) ?: run {
            val registered = Document(ID, collection.name).append(CONTEXT, context).append(AGGREGATE, aggregate)
            if (ddl.creates) {
                // The walk its creation records: kept with the document, so one registered before keeps its own.
                served[aggregate]?.let {
                    registered.append(ACCESS_WALK, MongoLogAccess.documentOf(AccessWalk.created(it)))
                }
            }
            insert(
                registered,
                "the log collection ${collection.name}",
                "its tails would not read the collection",
                "{_id: \"${collection.name}\", context: \"$context\", aggregate: \"$aggregate\"}",
            )
            // Read on the primary (MongoDatabases), which holds the record a duplicate key met: another node's insert.
            holderOf(collection.name)!!
        }
        check(holder.getString(CONTEXT) == context && holder.getString(AGGREGATE) == aggregate) {
            "The aggregate $aggregate of $context takes the log collection ${collection.name}, " +
                "which the aggregate ${holder.getString(AGGREGATE)} of ${holder.getString(CONTEXT)} has."
        }
    }

    /**
     * The partition count the registry records for the log's counters: [count] when none is recorded yet, under every
     * `ddl`, as the log starts ([MongoEventLog.createSchema]). Read first and written only when missing ([insert]), so
     * an account that may only read the registry starts a log whose count is recorded (#7230).
     */
    fun recordedPartitions(count: Int): Int {
        val marker = "$countersName${MongoEventLog.PARTITIONS_MARKER}"
        holderOf(marker)?.let { return it.getInteger(MongoEventLog.PARTITIONS) }
        insert(
            Document(ID, marker).append(MongoEventLog.PARTITIONS, count),
            "the partition count of the log's counters $countersName",
            "the log would not start",
            "{_id: \"$marker\", ${MongoEventLog.PARTITIONS}: $count}",
        )
        return holderOf(marker)!!.getInteger(MongoEventLog.PARTITIONS)
    }

    private fun holderOf(id: String): Document? = registry.find(Filters.eq(ID, id)).first()

    /**
     * Inserts [document], the registry's record of [what], which is missing; another node's insert of it first is no
     * failure. One this store may not write (an account without insert on the registry) is refused, naming
     * [consequence] and the record an operator inserts instead, [written]; a failure of the store's connection is thrown
     * as it is, to be retried.
     */
    private fun insert(document: Document, what: String, consequence: String, written: String) {
        try {
            registry.insertOne(document)
        } catch (failure: MongoException) {
            if (failure.isDuplicateKey()) return
            if (MongoFailures.isTransient(failure)) throw failure
            throw IllegalStateException(
                "The registry ${MongoEventLog.REGISTRY} has no record of $what, and this store could not write it " +
                    "(${failure.message}): $consequence. Let the store's account insert into " +
                    "${MongoEventLog.REGISTRY}, or insert $written into it.",
                failure,
            )
        }
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

    /**
     * Whether this log knows the collection named [name]: the registry lists it as last read, or this log made sure of
     * it. A tail reads the registry again whenever an open block names a collection it does not know ([MongoTailReads]).
     */
    fun lists(name: String): Boolean = known().any { it.name == name }

    /** Reads the registry again: the aggregate collections of the context, in registry order. */
    fun register(): List<AggregateCollection> =
        registry.find(Filters.eq(CONTEXT, context)).sort(Sorts.ascending(AGGREGATE))
            .map { AggregateCollection(it.getString(AGGREGATE), it.getString(ID), database, appendMillis, context) }
            .toList()
            .also { registered = it }
}
