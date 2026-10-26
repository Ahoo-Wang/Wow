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

    override fun instances(aggregate: NineXAggregate): Long =
        database.getCollection(aggregate.name + MongoNineXEventSource.SUFFIX)
            .countDocuments(Filters.and(Filters.eq(CONTEXT_NAME, aggregate.context), Filters.eq(VERSION, 1)))

    companion object {
        /** The suffix of a 9.x snapshot collection. */
        const val SUFFIX: String = "_snapshot"
        private const val ID = "_id"
        private const val CONTEXT_NAME = "contextName"
        private const val VERSION = "version"
    }
}
