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
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapter
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.snapshot.AbstractSnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend

class MongoSnapshotQueryBackendFactory(
    private val database: MongoDatabase,
    private val schemaSources: List<QuerySchemaSource> = emptyList(),
) : AbstractSnapshotQueryBackendFactory() {
    override fun createBinding(namedAggregate: NamedAggregate): QueryBackendBinding<SnapshotQueryBackend> {
        val materialized = namedAggregate.materialize()
        val collectionName = namedAggregate.toSnapshotCollectionName()
        val collection = database.getCollection(collectionName)
        val provider = DefaultQueryModelSchemaProvider(
            context = QuerySchemaContext(materialized, QueryModel.SNAPSHOT),
            sources = schemaSources,
            adapter = MongoQuerySchemaAdapter(collection, database),
        )
        return QueryBackendBinding(
            MongoSnapshotQueryBackend(materialized, collection),
            provider,
        )
    }
}
