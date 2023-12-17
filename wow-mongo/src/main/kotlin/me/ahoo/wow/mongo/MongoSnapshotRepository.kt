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
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.Version.Companion.UNINITIALIZED_VERSION
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.Documents.replaceAggregateIdToPrimaryKey
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.toJsonString
import org.bson.Document
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class MongoSnapshotRepository(private val database: MongoDatabase) : SnapshotRepository {
    companion object {
        val DEFAULT_REPLACE_OPTIONS: ReplaceOptions = ReplaceOptions().upsert(true)
    }

    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
        val snapshotCollectionName = aggregateId.toSnapshotCollectionName()
        return database.getCollection(snapshotCollectionName)
            .find(Filters.eq(Documents.ID_FIELD, aggregateId.id))
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
        val snapshotJsonString = snapshot.toJsonString()
        val document = Document.parse(snapshotJsonString)
            .replaceAggregateIdToPrimaryKey()
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
}
