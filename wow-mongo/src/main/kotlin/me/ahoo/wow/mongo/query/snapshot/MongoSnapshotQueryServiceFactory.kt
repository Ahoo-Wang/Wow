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

package me.ahoo.wow.mongo.query.snapshot

import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.snapshot.AbstractSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService

class MongoSnapshotQueryServiceFactory private constructor(
    private val database: MongoDatabase,
    private val queryGateway: QueryGateway?,
    @Suppress("UNUSED_PARAMETER") marker: Unit
) : AbstractSnapshotQueryServiceFactory() {
    @Deprecated("Use the constructor that requires QueryGateway.")
    constructor(database: MongoDatabase) : this(database, null, Unit)

    constructor(database: MongoDatabase, queryGateway: QueryGateway) : this(database, queryGateway, Unit)

    override fun createQueryService(namedAggregate: NamedAggregate): SnapshotQueryService<*> {
        val collectionName = namedAggregate.toSnapshotCollectionName()
        val collection = database.getCollection(collectionName)
        val materialized = namedAggregate.materialize()
        return queryGateway?.let { MongoSnapshotQueryService<Any>(materialized, collection, it) }
            ?: MongoSnapshotQueryService<Any>(materialized, collection)
    }
}
