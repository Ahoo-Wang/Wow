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

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Field
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.AGGREGATE_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.BRANCH
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.COMMAND_TYPE
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.FACTS
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.PARTITION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.REQUEST_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.SEQUENCE
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.TENANT_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.VERSION
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.VersionPages
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.bson.conversions.Bson

/**
 * The reads of one aggregate's streams in a context's log on MongoDB, each by the version key in the aggregate's
 * collection as a majority committed it ([AggregateCollection.committed]): a range of streams or of entries, the tail
 * with the request keys before it, a version's position, and the stream of a request. A tail read records the
 * aggregate's last sequence ([lastSequences]), so its next append lands after it without reading it.
 */
internal class MongoAggregateReads(
    private val dispatcher: CoroutineDispatcher,
    private val collections: MongoLogCollections,
    private val documents: MongoLogDocuments,
    private val lookups: MongoLogLookups,
    /** Each aggregate's last sequence as the log saw it, which a tail read records. */
    private val lastSequences: LastSequences,
    partitions: Int,
) {
    /** Every partition of the log: a lookup of the request key without the aggregate seeks it in each. */
    private val everyPartition: Bson = Filters.`in`(PARTITION, (0 until partitions).toList())

    /** A page of [VersionPages.SIZE] streams a `find`, each emitted before the next is read. */
    fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
        VersionPages.byVersion(fromVersion, toVersion, EventStreamRecord::version) { from ->
            dispatcher.mongo {
                val collection = collections.of(ref.type)
                collection.committed.find(
                    Filters.and(lookups.refFilter(ref), Filters.gte(VERSION, from), Filters.lte(VERSION, toVersion)),
                )
                    .projection(FACTS)
                    .sort(Sorts.ascending(VERSION))
                    .limit(VersionPages.SIZE)
                    .batchSize(VersionPages.SIZE)
                    .map { documents.streamOf(collection.aggregate, it) }
                    .toList()
            }
        }

    /**
     * One aggregation: the tail by the version key, then, through `$unionWith` on the same
     * collection, the request keys of the latest streams before it, newest first. The first batch holds them all
     * ([READ_TAIL_BATCH] streams of tail beyond them), so a typical read is one command. Its stages are BSON
     * already ([tailStages]): the driver renders builders on every call, and the last stage once more.
     */
    suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail {
        require(fromVersion >= 1) { "A stream has a version from 1." }
        require(earlier >= 0) { "At most a non-negative number of earlier requests." }
        return dispatcher.mongo {
            val collection = collections.of(ref.type)
            val pipeline = tailStages(ref, fromVersion, earlier, collection.name)
            val streams = ArrayList<EventStreamRecord>()
            val requests = ArrayList<CommittedRequest>()
            var last: LastSequence? = null
            collection.committed.aggregate(pipeline).batchSize(earlier + READ_TAIL_BATCH).forEach { document ->
                val version = document.getInteger(VERSION)
                if (last.let { it == null || version > it.version }) {
                    last = LastSequence(version, document.getLong(SEQUENCE))
                }
                if (document.getInteger(BRANCH) == 0) {
                    streams += documents.streamOf(collection.aggregate, document)
                } else {
                    requests += CommittedRequest(
                        document.getInteger(VERSION),
                        document.getString(REQUEST_ID),
                        document.getString(COMMAND_TYPE),
                    )
                }
            }
            streams.sortBy { it.version }
            requests.sortByDescending { it.version }
            last?.let { lastSequences[ref] = it }
            AggregateTail(streams, requests)
        }
    }

    /** A page of [VersionPages.SIZE] entries a `find`, as [read]. */
    fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> =
        VersionPages.byVersion(fromVersion, toVersion, { it.stream.version }) { from ->
            dispatcher.mongo {
                val collection = collections.of(ref.type)
                collection.committed.find(
                    Filters.and(lookups.refFilter(ref), Filters.gte(VERSION, from), Filters.lte(VERSION, toVersion)),
                )
                    .sort(Sorts.ascending(VERSION))
                    .limit(VersionPages.SIZE)
                    .batchSize(VersionPages.SIZE)
                    .map { documents.entryOf(collection.aggregate, it) }
                    .toList()
            }
        }

    suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = dispatcher.mongo {
        collections.of(ref.type).committed.find(Filters.and(lookups.refFilter(ref), Filters.eq(VERSION, version)))
            .projection(Projections.include(PARTITION, SEQUENCE))
            .first()
            ?.let { LogPosition(it.getInteger(PARTITION), it.getLong(SEQUENCE)) }
    }

    /**
     * The stream committed for [requestId] in [ref]'s partition of its collection: one indexed find. The request key
     * is unique within a partition, which is what an append of [ref] meets.
     */
    suspend fun findByRequestId(ref: AggregateRef, requestId: String): EventStreamRecord? =
        dispatcher.mongo {
            lookups.requestIn(collections.of(ref.type), ref, requestId)
        }

    /**
     * The stream committed for [requestId] within [tenantId] in [type]'s collection, in any partition: one find, an
     * index seek per partition.
     */
    suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        dispatcher.mongo { lookups.findByRequestIdIn(collections.of(type), everyPartition, tenantId, requestId) }

    /** [readTail]'s stages, as BSON: the dynamic matches and limit built here, the rest rendered once. */
    private fun tailStages(ref: AggregateRef, fromVersion: Int, earlier: Int, collection: String): List<Bson> {
        fun versions(operator: String) = BsonDocument(
            "\$match",
            BsonDocument(PARTITION, BsonInt32(lookups.partitionOf(ref)))
                .append(AGGREGATE_ID, BsonString(ref.id))
                .append(TENANT_ID, BsonString(ref.tenantId))
                .append(VERSION, BsonDocument(operator, BsonInt32(fromVersion))),
        )
        val tail = listOf(versions("\$gte"), TAIL_FACTS, TAIL_BRANCH)
        if (earlier == 0) return tail
        val keys = BsonArray(
            listOf(versions("\$lt"), KEYS_ORDER, BsonDocument("\$limit", BsonInt32(earlier)), KEYS_FIELDS, KEYS_BRANCH),
        )
        return tail + BsonDocument("\$unionWith", BsonDocument("coll", BsonString(collection)).append("pipeline", keys))
    }

    internal companion object {
        /** The streams of tail [readTail]'s first batch holds beyond the earlier request keys. */
        const val READ_TAIL_BATCH = 1000

        /** The fixed stages of [readTail], rendered once. */
        private val TAIL_FACTS = Aggregates.project(FACTS).toBsonDocument()
        private val TAIL_BRANCH = Aggregates.addFields(Field(BRANCH, 0)).toBsonDocument()
        private val KEYS_ORDER = Aggregates.sort(Sorts.descending(VERSION)).toBsonDocument()
        private val KEYS_FIELDS = Aggregates.project(
            Projections.fields(Projections.include(VERSION, REQUEST_ID, COMMAND_TYPE, SEQUENCE)),
        ).toBsonDocument()
        private val KEYS_BRANCH = Aggregates.addFields(Field(BRANCH, 1)).toBsonDocument()
    }
}
