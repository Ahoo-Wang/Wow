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
import me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapter
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.event.AbstractEventStreamQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryBackend

/** Pairs each aggregate's event-stream collection with its backend and the adapter reporting its native facts. */
class MongoEventStreamQueryBackendFactory(
    private val database: MongoDatabase,
) : AbstractEventStreamQueryBackendFactory() {
    override fun createBinding(namedAggregate: NamedAggregate): QueryBackendBinding<EventStreamQueryBackend> {
        val materialized = namedAggregate.materialize()
        val collection = database.getCollection(namedAggregate.toEventStreamCollectionName())
        return QueryBackendBinding(
            MongoEventStreamQueryBackend(materialized, collection),
            MongoQuerySchemaAdapter(collection, database, QueryModel.EVENT_STREAM),
        )
    }
}
