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
import com.mongodb.client.model.UpdateOptions
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

internal data class MongoSnapshotWrite(
    val collectionName: String,
    val id: String,
    val version: Int,
    val document: Document,
)

internal fun <S : Any> Snapshot<S>.toMongoSnapshotWrite(): MongoSnapshotWrite {
    val document = toDocument()
    val id = checkNotNull(document.getString(Documents.ID_FIELD)) {
        "Serialized Wow snapshot has no aggregate id."
    }
    val version = document[MessageRecords.VERSION]
    check(version is Int) {
        "Serialized Wow snapshot has no integer version."
    }
    return MongoSnapshotWrite(
        collectionName = aggregateId.toSnapshotCollectionName(),
        id = id,
        version = version,
        document = document,
    )
}

internal interface MongoSnapshotSaver : AutoCloseable {
    fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void>

    override fun close() = Unit
}

internal class DirectMongoSnapshotSaver(
    private val database: MongoDatabase,
) : MongoSnapshotSaver {
    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        val write = snapshot.toMongoSnapshotWrite()
        return database.getCollection(write.collectionName)
            .updateOne(
                Filters.eq(Documents.ID_FIELD, write.id),
                versionGuardedSnapshotReplacement(write.document),
                VERSION_GUARDED_UPDATE_OPTIONS,
            )
            .toMono()
            .doOnNext {
                check(it.wasAcknowledged()) {
                    "MongoDB did not acknowledge the snapshot save."
                }
            }.then()
    }

    private companion object {
        val VERSION_GUARDED_UPDATE_OPTIONS: UpdateOptions = UpdateOptions().upsert(true)
    }
}
