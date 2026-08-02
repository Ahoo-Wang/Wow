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

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.mql.MqlValues
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.Version.Companion.UNINITIALIZED_VERSION
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import org.bson.conversions.Bson
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class MongoSnapshotStore private constructor(
    private val database: MongoDatabase,
    val batchOptions: MongoSnapshotStoreBatchOptions,
    private val saver: MongoSnapshotSaver,
) : SnapshotStore {
    constructor(database: MongoDatabase) : this(
        database = database,
        batchOptions = MongoSnapshotStoreBatchOptions(),
        saver = DirectMongoSnapshotSaver(database),
    )

    constructor(
        database: MongoDatabase,
        batchOptions: MongoSnapshotStoreBatchOptions,
    ) : this(
        database = database,
        batchOptions = batchOptions,
        saver = if (batchOptions.enabled) {
            BatchMongoSnapshotSaver(
                database = database,
                options = batchOptions,
            )
        } else {
            DirectMongoSnapshotSaver(database)
        },
    )

    companion object {
        const val NAME = "mongo"
        val DEFAULT_REPLACE_OPTIONS: ReplaceOptions = ReplaceOptions().upsert(true)
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
        return saver.save(snapshot)
    }

    override fun close() {
        saver.close()
    }
}

internal fun versionGuardedSnapshotReplacement(
    snapshotDocument: Document,
): List<Bson> {
    val snapshotVersion = snapshotDocument[MessageRecords.VERSION]
    check(snapshotVersion is Int) {
        "Serialized Wow snapshot has no integer version."
    }
    val candidateVersion = MqlValues.of(snapshotVersion)
    val candidate = MqlValues.of(snapshotDocument)
    val stored = MqlValues.current()
    val normalizedStoredVersion = stored.getField(MessageRecords.VERSION)
        .isIntegerOr(candidateVersion)
    val replacement = normalizedStoredVersion.lte(candidateVersion)
        .cond(candidate, stored)
    return listOf(Aggregates.replaceWith(replacement))
}
