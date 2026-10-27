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
import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.AGGREGATE_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.FACTS
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.PARTITION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.REQUEST_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.TENANT_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.VERSION
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.log.Partitioner
import org.bson.conversions.Bson

/**
 * Where an aggregate's streams are in its log collection: its partition, the filter of its streams (over the version
 * key), its current version and the stream of a request.
 */
internal class MongoLogLookups(private val partitions: Int, private val documents: MongoLogDocuments) {
    fun partitionOf(ref: AggregateRef): Int = Partitioner.partitionOf(ref, partitions)

    /** The stream committed for [requestId] that an append of [ref] meets: the request key in [ref]'s partition. */
    fun requestIn(collection: AggregateCollection, ref: AggregateRef, requestId: String) =
        findByRequestIdIn(collection, Filters.eq(PARTITION, partitionOf(ref)), ref.tenantId, requestId)

    fun findByRequestIdIn(collection: AggregateCollection, partitions: Bson, tenantId: String, requestId: String) =
        collection.committed.find(
            Filters.and(partitions, Filters.eq(TENANT_ID, tenantId), Filters.eq(REQUEST_ID, requestId))
        )
            .projection(FACTS)
            .firstOrNull()
            ?.let { documents.streamOf(collection.aggregate, it) }

    fun currentVersion(collection: AggregateCollection, ref: AggregateRef): Int =
        collection.committed.find(refFilter(ref)).sort(Sorts.descending(VERSION)).limit(1).firstOrNull()
            ?.getInteger(VERSION) ?: 0

    /**
     * The streams of [ref]. The version key is a partial index over the documents with a version, so
     * a read uses it only when its filter implies one: a lookup of the latest stream by its key alone would scan the
     * collection.
     */
    fun refFilter(ref: AggregateRef): Bson = Filters.and(
        Filters.eq(PARTITION, partitionOf(ref)),
        Filters.eq(AGGREGATE_ID, ref.id),
        Filters.eq(TENANT_ID, ref.tenantId),
        Filters.exists(VERSION),
    )
}
