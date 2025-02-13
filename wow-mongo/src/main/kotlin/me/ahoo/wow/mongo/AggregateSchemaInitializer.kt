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

import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.infra.accessor.function.reactive.toBlockable
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import org.slf4j.LoggerFactory
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

object AggregateSchemaInitializer {
    private val log = LoggerFactory.getLogger(AggregateSchemaInitializer::class.java)
    const val AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME = "aggregateId_1_version_1"
    const val REQUEST_ID_UNIQUE_INDEX_NAME = "requestId_1"
    private val uniqueIndexOptions = IndexOptions().unique(true)
    private const val EVENT_STREAM_COLLECTION_SUFFIX = "_event_stream"
    private const val SNAPSHOT_COLLECTION_SUFFIX = "_snapshot"
    fun NamedAggregate.toEventStreamCollectionName(): String {
        return "${this.aggregateName}$EVENT_STREAM_COLLECTION_SUFFIX"
    }

    fun NamedAggregate.toSnapshotCollectionName(): String {
        return "${this.aggregateName}$SNAPSHOT_COLLECTION_SUFFIX"
    }

    fun MongoDatabase.ensureCollection(collectionName: String): Boolean {
        listCollectionNames().toFlux().collectList().toBlockable().block()!!.let {
            if (it.contains(collectionName)) {
                if (log.isInfoEnabled) {
                    log.info("Ensure Collection {} already exists,ignore create.", collectionName)
                }
                return false
            }
            if (log.isInfoEnabled) {
                log.info("Ensure Collection {} Creating.", collectionName)
            }
            this.createCollection(collectionName).toMono().block()
            if (log.isInfoEnabled) {
                log.info("Ensure Collection {} Created.", collectionName)
            }
            return true
        }
    }

    fun MongoCollection<Document>.createAggregateIdIndex() {
        createIndex(Indexes.hashed(MessageRecords.AGGREGATE_ID))
            .toMono().toBlockable().block()
    }

    fun MongoCollection<Document>.createAggregateIdAndVersionUniqueIndex() {
        createIndex(Indexes.ascending(MessageRecords.AGGREGATE_ID, MessageRecords.VERSION), uniqueIndexOptions)
            .toMono().toBlockable().block()
    }

    fun MongoCollection<Document>.createRequestIdUniqueIndex() {
        createIndex(Indexes.ascending(MessageRecords.REQUEST_ID), uniqueIndexOptions)
            .toMono().toBlockable().block()
    }

    fun MongoCollection<Document>.createAggregateIdAndRequestIdUniqueIndex() {
        createIndex(Indexes.ascending(MessageRecords.AGGREGATE_ID, MessageRecords.REQUEST_ID), uniqueIndexOptions)
            .toMono().toBlockable().block()
    }

    fun MongoCollection<Document>.createTenantIdIndex() {
        createIndex(Indexes.hashed(MessageRecords.TENANT_ID))
            .toMono().toBlockable().block()
    }
    fun MongoCollection<Document>.createOwnerIdIndex() {
        createIndex(Indexes.hashed(MessageRecords.OWNER_ID))
            .toMono().toBlockable().block()
    }
}
