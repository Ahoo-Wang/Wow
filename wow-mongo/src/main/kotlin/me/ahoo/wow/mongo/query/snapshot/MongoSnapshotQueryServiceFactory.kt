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
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import java.util.concurrent.ConcurrentHashMap

class MongoSnapshotQueryServiceFactory(private val database: MongoDatabase) : SnapshotQueryServiceFactory {
    private val queryServiceCache = ConcurrentHashMap<NamedAggregate, SnapshotQueryService<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> {
        return queryServiceCache.computeIfAbsent(namedAggregate.materialize()) {
            createQueryService(it)
        } as SnapshotQueryService<S>
    }

    private fun createQueryService(namedAggregate: NamedAggregate): SnapshotQueryService<*> {
        val collectionName = namedAggregate.toSnapshotCollectionName()
        val collection = database.getCollection(collectionName)
        return MongoSnapshotQueryService<Any>(namedAggregate.materialize(), collection)
    }
}
