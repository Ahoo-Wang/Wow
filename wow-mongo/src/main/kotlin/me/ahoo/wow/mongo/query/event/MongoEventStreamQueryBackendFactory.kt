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

package me.ahoo.wow.mongo.query.event

import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.query.event.AbstractEventStreamQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaValidationMode

class MongoEventStreamQueryBackendFactory(
    private val database: MongoDatabase,
    private val schemaSources: List<QuerySchemaSource> = emptyList(),
    private val validationMode: QuerySchemaValidationMode = QuerySchemaValidationMode.COMPATIBLE,
) :
    AbstractEventStreamQueryBackendFactory() {

    override fun createBackend(namedAggregate: NamedAggregate): EventStreamQueryBackend {
        val collectionName = namedAggregate.toEventStreamCollectionName()
        val collection = database.getCollection(collectionName)
        val materialized = namedAggregate.materialize()
        val provider = DefaultQueryModelSchemaProvider(
            context = QuerySchemaContext(materialized, QueryModel.EVENT_STREAM),
            sources = schemaSources,
            adapter = me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapter(
                collection,
                database,
                QueryModel.EVENT_STREAM,
                EventStreamFieldConverter,
            ),
        )
        return MongoEventStreamQueryBackend(
            namedAggregate = materialized,
            collection = collection,
            schemaProvider = provider,
            validationMode = validationMode,
        )
    }
}
