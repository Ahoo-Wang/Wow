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

import com.mongodb.MongoException
import com.mongodb.reactivestreams.client.MongoDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.infra.accessor.function.reactive.toBlockable
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

@Suppress("TooManyFunctions")
object AggregateSchemaInitializer {
    private val log = KotlinLogging.logger {}
    const val AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME = "aggregateId_1_version_1"
    const val REQUEST_ID_UNIQUE_INDEX_NAME = "requestId_1"
    private const val EVENT_STREAM_COLLECTION_SUFFIX = "_event_stream"
    private const val SNAPSHOT_COLLECTION_SUFFIX = "_snapshot"
    private const val SNAPSHOT_CHECKPOINT_COLLECTION_SUFFIX = "_snapshot_checkpoint"

    fun NamedAggregate.toEventStreamCollectionName(): String {
        return "${this.aggregateName}$EVENT_STREAM_COLLECTION_SUFFIX"
    }

    fun NamedAggregate.toSnapshotCollectionName(): String {
        return "${this.aggregateName}$SNAPSHOT_COLLECTION_SUFFIX"
    }

    fun NamedAggregate.toSnapshotCheckpointCollectionName(): String {
        return "${this.aggregateName}$SNAPSHOT_CHECKPOINT_COLLECTION_SUFFIX"
    }

    fun MongoDatabase.ensureCollection(collectionName: String): Boolean {
        listCollectionNames().toFlux().collectList().toBlockable().block()!!.let {
            if (it.contains(collectionName)) {
                log.info {
                    "Ensure Collection [$collectionName] already exists, ignore create."
                }
                return false
            }
            log.info {
                "Ensure Collection [$collectionName] Creating."
            }
            try {
                this.createCollection(collectionName).toMono().block()
            } catch (error: MongoException) {
                if (error.code != NAMESPACE_EXISTS_CODE) {
                    throw error
                }
                log.info {
                    "Collection [$collectionName] was created concurrently, continue initialization."
                }
                return false
            }
            log.info {
                "Collection [$collectionName] created."
            }
            return true
        }
    }

    private const val NAMESPACE_EXISTS_CODE = 48
}
