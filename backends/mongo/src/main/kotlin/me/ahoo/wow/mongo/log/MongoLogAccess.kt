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

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateManyModel
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.ACCESS
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.ACCESS_VERSION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.ACCESS_WALK
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.AGGREGATE_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.COMPLETED
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.CURSOR
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.HEADS
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.PARTITION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.SCHEMA
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.TENANT_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.VERSION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.WALKED
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.access.AggregatePage
import org.bson.Document
import org.bson.conversions.Bson

/**
 * The access attributes of a context's log on MongoDB and the walks that stamp them ([AccessWalks]): the guarded
 * stamps of a range of an aggregate's streams, a type's aggregates paged partition by partition, and each aggregate
 * type's walk, claimed and advanced in its registry document with majority writes and reads ([walks]).
 */
internal class MongoLogAccess(
    private val dispatcher: CoroutineDispatcher,
    private val collections: MongoLogCollections,
    private val lookups: MongoLogLookups,
    /** The registry the access walks are claimed in and read from: majority writes and reads, as the counters'. */
    private val walks: MongoCollection<Document>,
    private val partitions: Int,
) {
    /**
     * One update of the range by the version key, on the streams not stamped as of a later version. A stream not
     * stamped has no `access_version`, which `$lte` does not match, so the guard names its absence too; the count is of
     * the streams it matched, as a stamp that writes the same attributes again still counts them.
     */
    suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int): Int {
        if (from >= until) return 0
        return dispatcher.mongo {
            collections.of(ref.type).log.updateMany(
                stampFilter(ref, asOf, from, until),
                Updates.combine(Updates.set(ACCESS, Document(access)), Updates.set(ACCESS_VERSION, asOf)),
            ).matchedCount.toInt()
        }
    }

    /**
     * One unordered `bulkWrite` of the stamps' guarded `updateMany`s per aggregate type, each as [stamp] writes one:
     * each guard holds on the document it writes, so stamps that overlap leave the later version's attributes, in
     * whatever order the server applies them.
     */
    suspend fun stampAll(stamps: List<AccessStamp>): Int {
        val ranges = stamps.filter { it.from < it.until }
        if (ranges.isEmpty()) return 0
        return dispatcher.mongo {
            ranges.groupBy { it.ref.type }.entries.sumOf { (type, group) ->
                val updates = group.map {
                    UpdateManyModel<Document>(
                        stampFilter(it.ref, it.asOf, it.from, it.until),
                        Updates.combine(Updates.set(ACCESS, Document(it.access)), Updates.set(ACCESS_VERSION, it.asOf)),
                    )
                }
                collections.of(type).log.bulkWrite(updates, BulkWriteOptions().ordered(false)).matchedCount
            }
        }
    }

    /** The streams of [ref] from [from] until [until] not stamped as of a later version than [asOf]. */
    private fun stampFilter(ref: AggregateRef, asOf: Int, from: Int, until: Int): Bson = Filters.and(
        lookups.refFilter(ref),
        Filters.gte(VERSION, from),
        Filters.lt(VERSION, until),
        Filters.or(Filters.exists(ACCESS_VERSION, false), Filters.lte(ACCESS_VERSION, asOf)),
    )

    /**
     * Partition by partition, each by the version key (its aggregates' first streams); the cursor is the partition,
     * then [AggregatePage.cursorOf] within it.
     */
    suspend fun aggregates(type: AggregateType, after: String?, limit: Int): AggregatePage {
        require(limit > 0) { "A page holds at least one aggregate." }
        return dispatcher.mongo {
            val log = collections.existing(type)?.log ?: return@mongo AggregatePage(emptyList(), null)
            var partition = after?.substringBefore(CURSOR_PARTITION)?.toInt() ?: 0
            var key = after?.substringAfter(CURSOR_PARTITION)?.let(AggregatePage::keyOf)
            val refs = ArrayList<AggregateRef>(limit)
            while (partition < partitions) {
                val filters = mutableListOf(Filters.eq(PARTITION, partition), Filters.eq(VERSION, 1))
                key?.let { (id, tenant) ->
                    filters += Filters.or(
                        Filters.gt(AGGREGATE_ID, id),
                        Filters.and(Filters.eq(AGGREGATE_ID, id), Filters.gt(TENANT_ID, tenant)),
                    )
                }
                log.find(Filters.and(filters))
                    .sort(Sorts.ascending(AGGREGATE_ID, TENANT_ID))
                    .projection(Projections.include(AGGREGATE_ID, TENANT_ID))
                    .limit(limit - refs.size)
                    .forEach { refs += type.ref(it.getString(TENANT_ID), it.getString(AGGREGATE_ID)) }
                if (refs.size == limit) {
                    break
                }
                partition++
                key = null
            }
            val last = refs.takeIf { it.size == limit }?.last()
            AggregatePage(refs, last?.let { "$partition$CURSOR_PARTITION${AggregatePage.cursorOf(it)}" })
        }
    }

    /** Whether [type]'s collection exists: this log made sure of it, or the registry lists it. */
    suspend fun holds(type: AggregateType): Boolean = dispatcher.mongo { collections.existing(type) != null }

    /**
     * The registry document of [type]'s collection, its `access_walk` ([walkOf]); majority read. None before the
     * collection's first append: the read creates no collection.
     */
    suspend fun walk(type: AggregateType): AccessWalk? = dispatcher.mongo {
        walks.find(collections.registryOf(type)).first()
            ?.get(ACCESS_WALK, Document::class.java)
            ?.let(::walkOf)
    }

    /**
     * One conditional update of the registry document, majority written: concurrent claims serialize on it. A type
     * whose collection does not exist has no registry document, and the update matches none.
     */
    suspend fun claim(type: AggregateType, walk: AccessWalk, expected: String?): Boolean = dispatcher.mongo {
        val recorded = if (expected == null) Filters.eq(ACCESS_WALK, null) else Filters.eq(WALK_SCHEMA, expected)
        walks.updateOne(
            Filters.and(collections.registryOf(type), recorded),
            Updates.set(ACCESS_WALK, documentOf(walk)),
        ).matchedCount == 1L
    }

    suspend fun advance(
        type: AggregateType,
        schema: String,
        cursor: String?,
        walked: Long,
        completed: Boolean,
    ): Boolean = dispatcher.mongo {
        walks.updateOne(
            Filters.and(collections.registryOf(type), Filters.eq(WALK_SCHEMA, schema)),
            Updates.combine(
                Updates.set("$ACCESS_WALK.$CURSOR", cursor),
                Updates.set("$ACCESS_WALK.$WALKED", walked),
                Updates.set("$ACCESS_WALK.$COMPLETED", completed),
            ),
        ).matchedCount == 1L
    }

    internal companion object {
        private const val WALK_SCHEMA = "$ACCESS_WALK.$SCHEMA"

        /** Ends the partition of an [aggregates] cursor. */
        private const val CURSOR_PARTITION = "/"

        fun documentOf(walk: AccessWalk): Document = Document(SCHEMA, walk.schema)
            .append(HEADS, Document(walk.heads.toSortedMap().mapKeys { (partition, _) -> partition.toString() }))
            .append(CURSOR, walk.cursor)
            .append(WALKED, walk.walked)
            .append(COMPLETED, walk.completed)

        fun walkOf(document: Document): AccessWalk = AccessWalk(
            schema = document.getString(SCHEMA),
            heads = document.get(HEADS, Document::class.java).entries
                .associate { (partition, head) -> partition.toInt() to (head as Number).toLong() },
            cursor = document.getString(CURSOR),
            walked = (document[WALKED] as Number?)?.toLong() ?: 0,
            completed = document.getBoolean(COMPLETED, false),
        )
    }
}
