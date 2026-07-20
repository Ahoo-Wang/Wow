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
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.serialization.MessageRecords

class EventStreamSchemaInitializer(
    private val database: MongoDatabase,
    /**
     * Unable to enable sharded collection when aggregate ID is used as shard key in sharded cluster mode.
     *
     * It is recommended to use the command sender idempotency checker
     *
     * @see me.ahoo.wow.infra.idempotency.IdempotencyChecker
     * @see <a href="https://www.mongodb.com/docs/manual/core/sharding-shard-key/#unique-indexes">Shard Keys</a>
     */
    private val enableRequestIdUniqueIndex: Boolean = false
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    fun initAll() {
        MetadataSearcher.namedAggregateType.forEach { namedAggregate, _ ->
            initSchema(namedAggregate)
        }
    }

    fun initSchema(namedAggregate: NamedAggregate) {
        val collectionName = namedAggregate.toEventStreamCollectionName()
        log.info {
            "Init NamedAggregate Schema [$namedAggregate] to Database:[${database.name}] CollectionName [$collectionName]"
        }
        database.ensureCollection(collectionName)
        val eventStreamCollection = database.getCollection(collectionName)
        val requestIdIndex = if (enableRequestIdUniqueIndex) {
            ascendingIndex(MessageRecords.REQUEST_ID, unique = true)
        } else {
            ascendingIndex(MessageRecords.AGGREGATE_ID, MessageRecords.REQUEST_ID, unique = true)
        }
        val forbiddenIndexes = if (enableRequestIdUniqueIndex) {
            listOf(
                ascendingIndex(MessageRecords.AGGREGATE_ID, MessageRecords.REQUEST_ID, unique = true),
            )
        } else {
            listOf(ascendingIndex(MessageRecords.REQUEST_ID, unique = true))
        }
        eventStreamCollection.reconcileIndexes(
            expectedIndexes = listOf(
                hashedIndex(MessageRecords.AGGREGATE_ID),
                ascendingIndex(MessageRecords.AGGREGATE_ID, MessageRecords.VERSION, unique = true),
                requestIdIndex,
                hashedIndex(MessageRecords.TENANT_ID),
                hashedIndex(MessageRecords.OWNER_ID),
            ),
            forbiddenIndexes = forbiddenIndexes,
        )
    }
}
