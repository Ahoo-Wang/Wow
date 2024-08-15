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
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import java.util.concurrent.ConcurrentHashMap

class MongoEventStreamQueryServiceFactory(private val database: MongoDatabase) : EventStreamQueryServiceFactory {
    private val queryServiceCache = ConcurrentHashMap<NamedAggregate, EventStreamQueryService>()

    override fun create(namedAggregate: NamedAggregate): EventStreamQueryService {
        return queryServiceCache.computeIfAbsent(namedAggregate.materialize()) {
            createQueryService(it)
        }
    }

    private fun createQueryService(namedAggregate: NamedAggregate): EventStreamQueryService {
        val collectionName = namedAggregate.toEventStreamCollectionName()
        val collection = database.getCollection(collectionName)
        return MongoEventStreamQueryService(namedAggregate.materialize(), collection)
    }
}
