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
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCheckpointCollectionName
import me.ahoo.wow.serialization.MessageRecords

class SnapshotCheckpointSchemaInitializer(private val database: MongoDatabase) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    fun initAll() {
        MetadataSearcher.namedAggregateType.forEach { namedAggregate, _ ->
            initSchema(namedAggregate)
        }
    }

    fun initSchema(namedAggregate: NamedAggregate) {
        val collectionName = namedAggregate.toSnapshotCheckpointCollectionName()
        log.info {
            "Init NamedAggregate checkpoint schema [$namedAggregate] to Database:[${database.name}] " +
                "CollectionName [$collectionName]"
        }
        database.ensureCollection(collectionName)
        database.getCollection(collectionName).reconcileIndexes(
            listOf(
                ascendingIndex(
                    MessageRecords.TENANT_ID,
                    MessageRecords.AGGREGATE_ID,
                    MessageRecords.VERSION,
                    unique = true,
                ),
            ),
        )
    }
}
