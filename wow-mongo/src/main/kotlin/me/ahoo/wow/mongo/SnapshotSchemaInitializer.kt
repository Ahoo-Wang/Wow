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

package me.ahoo.wow.mongo

import com.mongodb.reactivestreams.client.MongoDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.mongo.AggregateSchemaInitializer.ensureCollection
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords

class SnapshotSchemaInitializer(private val database: MongoDatabase) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    fun initAll() {
        MetadataSearcher.namedAggregateType.forEach { namedAggregate, _ ->
            initSchema(namedAggregate)
        }
    }

    fun initSchema(namedAggregate: NamedAggregate) {
        val collectionName = namedAggregate.toSnapshotCollectionName()
        log.info {
            "Init NamedAggregate Schema [$namedAggregate] to Database:[${database.name}] CollectionName [$collectionName]"
        }
        database.ensureCollection(collectionName)
        val snapshotCollection = database.getCollection(collectionName)
        snapshotCollection.reconcileIndexes(
            listOf(
                hashedIndex(MessageRecords.TENANT_ID),
                hashedIndex(MessageRecords.OWNER_ID),
                hashedIndex(Documents.ID_FIELD),
                hashedIndex(StateAggregateRecords.DELETED),
            ),
        )
    }
}
