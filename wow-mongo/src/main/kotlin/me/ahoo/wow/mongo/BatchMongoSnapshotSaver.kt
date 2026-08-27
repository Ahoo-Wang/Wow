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
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.infra.batch.BatchCloseTimeoutException
import me.ahoo.wow.infra.batch.BatchClosedException
import me.ahoo.wow.infra.batch.BatchOptions
import me.ahoo.wow.infra.batch.BatchOverflowException
import me.ahoo.wow.infra.batch.BatchWriter
import me.ahoo.wow.infra.batch.KeyedBatchCoordinator
import me.ahoo.wow.metrics.WowMetrics
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

internal class BatchMongoSnapshotSaver(
    database: MongoDatabase,
    private val options: MongoSnapshotStoreBatchOptions,
    private val closeTimeout: Duration = DEFAULT_CLOSE_TIMEOUT,
    metrics: WowMetrics = WowMetrics.NONE,
) : MongoSnapshotSaver {
    private data class MappedCloseTimeout(
        val source: BatchCloseTimeoutException,
        val mapped: MongoSnapshotStoreBatchCloseTimeoutException,
    )

    init {
        require(!closeTimeout.isNegative && !closeTimeout.isZero) {
            "closeTimeout must be positive."
        }
    }

    private val mappedCloseTimeout = AtomicReference<MappedCloseTimeout?>()
    private val coordinator = KeyedBatchCoordinator(
        name = MongoSnapshotStore::class.simpleName!!,
        options = BatchOptions(
            maxSize = options.maxSize,
            maxDelay = options.maxDelay,
            maxPendingItems = options.maxPendingSaves,
        ),
        laneCount = options.laneCount,
        keySelector = { write: MongoSnapshotWrite ->
            write.collectionName to write.id
        },
        writer = BatchWriter(MongoSnapshotBatchWriter(database)::write),
        metrics = metrics,
    )

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        return coordinator.submit {
            snapshot.toMongoSnapshotWrite()
        }.onErrorMap(::toMongoSnapshotBatchError)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        try {
            coordinator.close(closeTimeout)
        } catch (error: Throwable) {
            throw toMongoSnapshotBatchError(error)
        }
    }

    private fun toMongoSnapshotBatchError(error: Throwable): Throwable {
        return when (error) {
            is BatchOverflowException ->
                MongoSnapshotStoreBatchOverflowException(options.maxPendingSaves)

            is BatchClosedException ->
                IllegalStateException("MongoSnapshotStore is closed.")

            is BatchCloseTimeoutException -> mapCloseTimeout(error)

            else -> error
        }
    }

    private fun mapCloseTimeout(
        error: BatchCloseTimeoutException,
    ): MongoSnapshotStoreBatchCloseTimeoutException {
        while (true) {
            val current = mappedCloseTimeout.get()
            if (current?.source === error) {
                return current.mapped
            }
            val mapped = MappedCloseTimeout(
                source = error,
                mapped = MongoSnapshotStoreBatchCloseTimeoutException(error.timeout),
            )
            if (mappedCloseTimeout.compareAndSet(current, mapped)) {
                return mapped.mapped
            }
        }
    }

    private companion object {
        val DEFAULT_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
