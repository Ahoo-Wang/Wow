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
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.infra.batch.BatchCloseTimeoutException
import me.ahoo.wow.infra.batch.BatchClosedException
import me.ahoo.wow.infra.batch.BatchCoordinator
import me.ahoo.wow.infra.batch.BatchOptions
import me.ahoo.wow.infra.batch.BatchOverflowException
import me.ahoo.wow.infra.batch.BatchWriter
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import org.bson.Document
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

internal data class MongoEventStreamAppend(
    val eventStream: DomainEventStream,
    val document: Document,
    val collectionName: String,
)

internal class BatchMongoEventStreamAppender(
    database: MongoDatabase,
    private val options: MongoEventStoreBatchOptions,
    private val closeTimeout: Duration = DEFAULT_CLOSE_TIMEOUT,
) : MongoEventStreamAppender {
    private data class MappedCloseTimeout(
        val source: BatchCloseTimeoutException,
        val mapped: MongoEventStoreBatchCloseTimeoutException,
    )

    init {
        require(!closeTimeout.isNegative && !closeTimeout.isZero) {
            "closeTimeout must be positive."
        }
    }

    private val mappedCloseTimeout = AtomicReference<MappedCloseTimeout?>()
    private val coordinator = BatchCoordinator(
        name = MongoEventStore::class.simpleName!!,
        options = BatchOptions(
            maxSize = options.maxSize,
            maxDelay = options.maxDelay,
            maxPendingItems = options.maxPendingAppends,
        ),
        writer = BatchWriter(MongoEventStreamBatchWriter(database)::write),
    )

    override fun append(eventStream: DomainEventStream): Mono<Void> {
        return coordinator.submit {
            MongoEventStreamAppend(
                eventStream = eventStream,
                document = eventStream.toDocument(),
                collectionName = eventStream.toEventStreamCollectionName(),
            )
        }.onErrorMap(::toMongoBatchError)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        try {
            coordinator.close(closeTimeout)
        } catch (error: Throwable) {
            throw toMongoBatchError(error)
        }
    }

    private fun toMongoBatchError(error: Throwable): Throwable {
        return when (error) {
            is BatchOverflowException ->
                MongoEventStoreBatchOverflowException(options.maxPendingAppends)

            is BatchClosedException ->
                IllegalStateException("MongoEventStore is closed.")

            is BatchCloseTimeoutException -> mapCloseTimeout(error)

            else -> error
        }
    }

    private fun mapCloseTimeout(
        error: BatchCloseTimeoutException,
    ): MongoEventStoreBatchCloseTimeoutException {
        while (true) {
            val current = mappedCloseTimeout.get()
            if (current?.source === error) {
                return current.mapped
            }
            val mapped = MappedCloseTimeout(
                source = error,
                mapped = MongoEventStoreBatchCloseTimeoutException(error.timeout),
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
