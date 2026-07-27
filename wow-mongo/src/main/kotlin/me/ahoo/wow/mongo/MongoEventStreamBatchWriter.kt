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
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.infra.batch.BatchItemResult
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

internal class MongoEventStreamBatchWriter(
    private val database: MongoDatabase,
) {
    fun write(batch: List<MongoEventStreamAppend>): Mono<List<BatchItemResult>> {
        val groups = batch.withIndex()
            .groupBy { it.value.collectionName }
            .values
        return Flux.fromIterable(groups)
            .flatMap { indexedGroup ->
                writeCollection(indexedGroup.map { it.value })
                    .map { outcomes ->
                        indexedGroup.zip(outcomes).map { (indexed, outcome) ->
                            indexed.index to outcome
                        }
                    }
            }
            .flatMapIterable { it }
            .collectMap(
                { it.first },
                { it.second },
            )
            .map { indexedResults ->
                batch.indices.map { index ->
                    checkNotNull(indexedResults[index]) {
                        "MongoDB batch writer did not produce a result for input[$index]."
                    }
                }
            }
    }

    private fun writeCollection(
        batch: List<MongoEventStreamAppend>,
    ): Mono<List<BatchItemResult>> {
        return Mono.defer {
            database.getCollection(batch.first().collectionName)
                .insertMany(
                    batch.map(MongoEventStreamAppend::document),
                    UNORDERED_INSERT_MANY_OPTIONS,
                )
                .toMono()
        }.doOnNext {
            check(it.wasAcknowledged()) {
                "MongoDB did not acknowledge the event stream batch append."
            }
        }.thenReturn(
            batch.map<MongoEventStreamAppend, BatchItemResult> {
                BatchItemResult.Success
            }
        )
            .onErrorResume(MongoBulkWriteException::class.java) { error ->
                resolveBulkWriteError(batch, error).toMono()
            }.onErrorResume { error ->
                batch.map<MongoEventStreamAppend, BatchItemResult> {
                    BatchItemResult.Failure(error)
                }.toMono()
            }
    }

    private fun resolveBulkWriteError(
        batch: List<MongoEventStreamAppend>,
        error: MongoBulkWriteException,
    ): List<BatchItemResult> {
        val writeErrors = error.writeErrors
        val errorsByIndex = writeErrors.associateBy { it.index }
        val writeConcernError = error.writeConcernError?.toWowError(error)
        val invalidWriteErrors = errorsByIndex.size != writeErrors.size ||
            errorsByIndex.keys.any { it !in batch.indices }
        val invalidWriteResult = writeConcernError == null &&
            (
                writeErrors.isEmpty() ||
                    !error.writeResult.wasAcknowledged() ||
                    error.writeResult.insertedCount != batch.size - writeErrors.size
                )
        if (invalidWriteErrors || invalidWriteResult) {
            return batch.map {
                BatchItemResult.Failure(error)
            }
        }
        return batch.mapIndexed { index, append ->
            val writeError = errorsByIndex[index]
            when {
                writeError != null -> {
                    BatchItemResult.Failure(
                        writeError.toWowError(append.eventStream, error)
                    )
                }

                writeConcernError != null -> BatchItemResult.Failure(writeConcernError)
                else -> BatchItemResult.Success
            }
        }
    }

    private companion object {
        val UNORDERED_INSERT_MANY_OPTIONS: InsertManyOptions =
            InsertManyOptions().ordered(false)
    }
}
