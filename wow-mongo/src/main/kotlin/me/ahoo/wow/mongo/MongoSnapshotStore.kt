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

import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.Version.Companion.UNINITIALIZED_VERSION
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.VersionedSnapshotStore
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCheckpointCollectionName
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class MongoSnapshotStore(private val database: MongoDatabase) : VersionedSnapshotStore {
    companion object {
        const val NAME = "mongo"
        val DEFAULT_REPLACE_OPTIONS: ReplaceOptions = ReplaceOptions().upsert(true)
        private const val PAYLOAD_FIELD = "payload"
        private val CHECKPOINT_UPSERT_OPTIONS = UpdateOptions().upsert(true)
    }

    override val name: String
        get() = NAME

    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
        val snapshotCollectionName = aggregateId.toSnapshotCollectionName()
        return database.getCollection(snapshotCollectionName)
            .find(Filters.eq(Documents.ID_FIELD, aggregateId.id))
            .limit(1)
            .first()
            .toMono()
            .map {
                mapSnapshot(aggregateId, it)
            }
    }

    override fun getVersion(aggregateId: AggregateId): Mono<Int> {
        val snapshotCollectionName = aggregateId.toSnapshotCollectionName()
        return database.getCollection(snapshotCollectionName)
            .find(Filters.eq(Documents.ID_FIELD, aggregateId.id))
            .projection(Document(MessageRecords.VERSION, 1))
            .limit(1)
            .first()
            .toMono()
            .map {
                it.getInteger(MessageRecords.VERSION)
            }.defaultIfEmpty(UNINITIALIZED_VERSION)
    }

    private fun <S : Any> mapSnapshot(
        aggregateId: AggregateId,
        document: Document
    ): Snapshot<S> {
        val snapshot = document.toSnapshot<S>()
        require(aggregateId == snapshot.aggregateId) {
            "aggregateId: $aggregateId != snapshot.aggregateId: ${snapshot.aggregateId}"
        }
        return snapshot
    }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        val snapshotCollectionName = snapshot.aggregateId.toSnapshotCollectionName()
        val document = snapshot.toDocument()
        return database.getCollection(snapshotCollectionName)
            .replaceOne(
                Filters.eq(Documents.ID_FIELD, snapshot.aggregateId.id),
                document,
                DEFAULT_REPLACE_OPTIONS,
            )
            .toMono()
            .doOnNext {
                check(it.wasAcknowledged())
            }.then()
    }

    override fun <S : Any> loadAtOrBefore(
        aggregateId: AggregateId,
        maxVersion: Int,
    ): Mono<Snapshot<S>> {
        require(maxVersion >= 0) {
            "maxVersion must be greater than or equal to 0."
        }
        val collectionName = aggregateId.toSnapshotCheckpointCollectionName()
        return database.getCollection(collectionName)
            .find(
                Filters.and(
                    Filters.eq(MessageRecords.AGGREGATE_ID, aggregateId.id),
                    Filters.eq(MessageRecords.TENANT_ID, aggregateId.tenantId),
                    Filters.lte(MessageRecords.VERSION, maxVersion),
                ),
            )
            .sort(Sorts.descending(MessageRecords.VERSION))
            .limit(1)
            .first()
            .toMono()
            .map { checkpoint ->
                val payload = checkNotNull(checkpoint.get(PAYLOAD_FIELD, Document::class.java)) {
                    "Mongo snapshot checkpoint payload is missing for [$aggregateId]."
                }
                mapSnapshot(aggregateId, payload)
            }
    }

    override fun <S : Any> saveCheckpoint(snapshot: Snapshot<S>): Mono<Void> {
        require(snapshot.version > 0) {
            "checkpoint version must be greater than 0."
        }
        val aggregateId = snapshot.aggregateId
        val collectionName = aggregateId.toSnapshotCheckpointCollectionName()
        val checkpointId = Document(MessageRecords.TENANT_ID, aggregateId.tenantId)
            .append(MessageRecords.AGGREGATE_ID, aggregateId.id)
            .append(MessageRecords.VERSION, snapshot.version)
        return database.getCollection(collectionName)
            .updateOne(
                Filters.eq(Documents.ID_FIELD, checkpointId),
                Updates.combine(
                    Updates.setOnInsert(MessageRecords.CONTEXT_NAME, aggregateId.contextName),
                    Updates.setOnInsert(MessageRecords.AGGREGATE_NAME, aggregateId.aggregateName),
                    Updates.setOnInsert(MessageRecords.AGGREGATE_ID, aggregateId.id),
                    Updates.setOnInsert(MessageRecords.TENANT_ID, aggregateId.tenantId),
                    Updates.setOnInsert(MessageRecords.VERSION, snapshot.version),
                    Updates.setOnInsert(PAYLOAD_FIELD, snapshot.toDocument()),
                ),
                CHECKPOINT_UPSERT_OPTIONS,
            )
            .toMono()
            .doOnNext { result ->
                check(result.wasAcknowledged()) {
                    "Mongo snapshot checkpoint write was not acknowledged for [$aggregateId] version " +
                        "[${snapshot.version}]."
                }
            }
            .then()
    }
}
