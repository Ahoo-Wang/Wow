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

package me.ahoo.wow.migrate

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import org.bson.Document

/**
 * The snapshots of a 9.x MongoDB store, read by their storage format (9.x `wow-mongo`'s `MongoSnapshotStore`): one
 * collection per aggregate, `<aggregateName>_snapshot`, one document per instance, its aggregate id as `_id` and its
 * decimals as `Decimal128` (read exact, [MongoNineXEventSource.toNode]). The tool only reads it.
 */
internal class MongoNineXSnapshotSource(private val database: MongoDatabase) : NineXSnapshotSource {
    override fun snapshots(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int): List<NineXSnapshot> {
        val filters = listOfNotNull(Filters.eq(CONTEXT_NAME, aggregate.context), after?.let { Filters.gt(ID, it.id) })
        return database.getCollection(aggregate.name + SUFFIX)
            .find(Filters.and(filters))
            .sort(Sorts.ascending(ID))
            .limit(limit)
            .map { NineXSnapshot.parse(MongoNineXEventSource.toNode(it)) }
            .toList()
    }

    override fun snapshot(aggregate: NineXAggregate, id: String): NineXSnapshot? =
        database.getCollection(aggregate.name + SUFFIX)
            .find(Filters.and(Filters.eq(CONTEXT_NAME, aggregate.context), Filters.eq(ID, id)))
            .first()
            ?.let { NineXSnapshot.parse(MongoNineXEventSource.toNode(it)) }

    /**
     * No index orders `snapshotTime`, so the pages read the collection once in `_id` order, each up to [limit]
     * snapshots taken since; a document without a numeric `snapshotTime` is taken as one ({ `$not`: { `$lt` } }).
     */
    override fun takenSince(aggregate: NineXAggregate, from: Long, after: String?, limit: Int): List<NineXSnapshot> {
        val filters = listOfNotNull(
            Filters.eq(CONTEXT_NAME, aggregate.context),
            Filters.not(Filters.lt(SNAPSHOT_TIME, from)),
            after?.let { Filters.gt(ID, it) },
        )
        return database.getCollection(aggregate.name + SUFFIX)
            .find(Filters.and(filters))
            .sort(Sorts.ascending(ID))
            .limit(limit)
            .map { NineXSnapshot.parse(MongoNineXEventSource.toNode(it)) }
            .toList()
    }

    override fun instances(aggregate: NineXAggregate): Long =
        database.getCollection(aggregate.name + MongoNineXEventSource.SUFFIX)
            .countDocuments(Filters.and(Filters.eq(CONTEXT_NAME, aggregate.context), Filters.eq(VERSION, 1)))

    override fun deleted(aggregate: NineXAggregate): Long = database.getCollection(aggregate.name + SUFFIX)
        .countDocuments(Filters.and(Filters.eq(CONTEXT_NAME, aggregate.context), Filters.eq(DELETED, true)))

    /** One aggregation of [aggregate]'s snapshots: the keys of their `tags`, each with its values' counts. */
    override fun tags(aggregate: NineXAggregate): Map<String, TagValueStats> = MongoTags.stats(
        database.getCollection(aggregate.name + SUFFIX).aggregate(
            listOf(
                Document("\$match", Document(CONTEXT_NAME, aggregate.context).append(TAGS, Document("\$type", "object"))),
            ) + MongoTags.counts("\$$TAGS"),
        ).toList(),
    )

    /** The instances whose snapshot holds the values, in id order. */
    override fun tagSamples(
        aggregate: NineXAggregate,
        key: String,
        problem: TagValueProblem,
        limit: Int
    ): List<String> {
        val holding = MongoTags.holding(TAGS, key, problem) ?: return emptyList()
        return database.getCollection(aggregate.name + SUFFIX).aggregate(
            listOf(Document("\$match", Document(CONTEXT_NAME, aggregate.context))) + holding +
                listOf(Document("\$sort", Document(ID, 1)), Document("\$limit", limit)),
        ).map { it.get(ID).toString() }.toList()
    }

    companion object {
        /** The suffix of a 9.x snapshot collection. */
        const val SUFFIX: String = "_snapshot"
        private const val ID = "_id"
        private const val CONTEXT_NAME = "contextName"
        private const val VERSION = "version"
        private const val DELETED = "deleted"
        private const val SNAPSHOT_TIME = "snapshotTime"
    }
}
