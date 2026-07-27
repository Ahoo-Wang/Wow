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

import com.mongodb.MongoBulkWriteException
import com.mongodb.bulk.BulkWriteResult
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.infra.batch.BatchItemResult
import org.bson.Document
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

internal class MongoSnapshotBatchWriter(
    private val database: MongoDatabase,
) {
    private data class SnapshotKey(
        val collectionName: String,
        val id: String,
    )

    fun write(batch: List<MongoSnapshotWrite>): Mono<List<BatchItemResult>> {
        require(batch.isNotEmpty()) { "MongoDB snapshot batch must not be empty." }
        val coalesced = coalesce(batch)
        val collectionGroups = coalesced.groupBy(MongoSnapshotWrite::collectionName).values
        return Flux.fromIterable(collectionGroups)
            .flatMap(::writeCollection)
            .flatMapIterable { it }
            .collectMap(
                { it.first },
                { it.second },
            )
            .map { resultsByKey ->
                batch.map { write ->
                    checkNotNull(resultsByKey[write.toKey()]) {
                        "MongoDB snapshot batch writer did not produce a result for " +
                            "${write.collectionName}/${write.id}."
                    }
                }
            }
    }

    private fun coalesce(batch: List<MongoSnapshotWrite>): List<MongoSnapshotWrite> {
        return batch.groupBy { it.toKey() }
            .values
            .map { sameAggregate ->
                sameAggregate.reduce { selected, candidate ->
                    if (candidate.version >= selected.version) {
                        candidate
                    } else {
                        selected
                    }
                }
            }
    }

    private fun writeCollection(
        batch: List<MongoSnapshotWrite>,
    ): Mono<List<Pair<SnapshotKey, BatchItemResult>>> {
        val models = batch.map { write ->
            UpdateOneModel<Document>(
                Filters.eq(Documents.ID_FIELD, write.id),
                versionGuardedSnapshotReplacement(write.document),
                VERSION_GUARDED_UPDATE_OPTIONS,
            )
        }
        return Mono.defer {
            database.getCollection(batch.first().collectionName)
                .bulkWrite(models, UNORDERED_BULK_WRITE_OPTIONS)
                .toMono()
        }.map { result ->
            check(result.hasConsistentUpdateMetadata(batch.size)) {
                "MongoDB snapshot batch result does not account for every update."
            }
            batch.map { it.toKey() to (BatchItemResult.Success as BatchItemResult) }
        }.onErrorResume(MongoBulkWriteException::class.java) { error ->
            batch.zip(resolveBulkWriteError(batch.size, error))
                .map { (write, result) -> write.toKey() to result }
                .toMono()
        }.onErrorResume { error ->
            batch.map { it.toKey() to BatchItemResult.Failure(error) }.toMono()
        }
    }

    private fun resolveBulkWriteError(
        operationCount: Int,
        error: MongoBulkWriteException,
    ): List<BatchItemResult> {
        val writeErrors = error.writeErrors
        val errorsByIndex = writeErrors.associateBy { it.index }
        val writeConcernError = error.writeConcernError?.toWowError(error)
        val invalidWriteErrors = errorsByIndex.size != writeErrors.size ||
            errorsByIndex.keys.any { it !in 0 until operationCount }
        val invalidWriteResult = writeConcernError == null &&
            (
                writeErrors.isEmpty() ||
                    !error.writeResult.hasConsistentUpdateMetadata(
                        operationCount = operationCount,
                        failedIndices = errorsByIndex.keys,
                    )
                )
        if (invalidWriteErrors || invalidWriteResult) {
            return List(operationCount) {
                BatchItemResult.Failure(error)
            }
        }
        return List(operationCount) { index ->
            val writeError = errorsByIndex[index]
            when {
                writeError != null -> BatchItemResult.Failure(writeError.toWowError(error))
                writeConcernError != null -> BatchItemResult.Failure(writeConcernError)
                else -> BatchItemResult.Success
            }
        }
    }

    private fun BulkWriteResult.successfulUpdateCount(): Int {
        return matchedCount + upserts.size
    }

    private fun BulkWriteResult.hasConsistentUpdateMetadata(
        operationCount: Int,
        failedIndices: Set<Int> = emptySet(),
    ): Boolean {
        if (!wasAcknowledged()) {
            return false
        }
        val upsertIndices = upserts.map { it.index }
        return insertedCount == 0 &&
            deletedCount == 0 &&
            inserts.isEmpty() &&
            matchedCount >= 0 &&
            upsertIndices.distinct().size == upsertIndices.size &&
            upsertIndices.all { it in 0 until operationCount && it !in failedIndices } &&
            successfulUpdateCount() == operationCount - failedIndices.size
    }

    private fun MongoSnapshotWrite.toKey(): SnapshotKey {
        return SnapshotKey(
            collectionName = collectionName,
            id = id,
        )
    }

    private companion object {
        val VERSION_GUARDED_UPDATE_OPTIONS: UpdateOptions = UpdateOptions().upsert(true)
        val UNORDERED_BULK_WRITE_OPTIONS: BulkWriteOptions = BulkWriteOptions().ordered(false)
    }
}
