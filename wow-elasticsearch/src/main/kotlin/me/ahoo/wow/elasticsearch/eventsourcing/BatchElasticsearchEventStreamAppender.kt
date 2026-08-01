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

package me.ahoo.wow.elasticsearch.eventsourcing

import co.elastic.clients.elasticsearch._types.Refresh
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.infra.batch.BatchCloseTimeoutException
import me.ahoo.wow.infra.batch.BatchClosedException
import me.ahoo.wow.infra.batch.BatchObserver
import me.ahoo.wow.infra.batch.BatchOptions
import me.ahoo.wow.infra.batch.BatchOverflowException
import me.ahoo.wow.infra.batch.BatchWriter
import me.ahoo.wow.infra.batch.KeyedBatchCoordinator
import me.ahoo.wow.serialization.toLinkedHashMap
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

internal class BatchElasticsearchEventStreamAppender(
    elasticsearchClient: ReactiveElasticsearchClient,
    refreshPolicy: Refresh,
    private val options: ElasticsearchEventStoreBatchOptions,
    observer: BatchObserver = BatchObserver.NOOP,
    private val closeTimeout: Duration = DEFAULT_CLOSE_TIMEOUT,
) : ElasticsearchEventStreamAppender {
    private data class MappedCloseTimeout(
        val source: BatchCloseTimeoutException,
        val mapped: ElasticsearchEventStoreBatchCloseTimeoutException,
    )

    init {
        require(!closeTimeout.isNegative && !closeTimeout.isZero) {
            "closeTimeout must be positive."
        }
    }

    private val mappedCloseTimeout = AtomicReference<MappedCloseTimeout?>()
    private val coordinator = KeyedBatchCoordinator(
        name = ElasticsearchEventStore::class.simpleName!!,
        options = BatchOptions(
            maxSize = options.maxSize,
            maxDelay = options.maxDelay,
            maxPendingItems = options.maxPendingAppends,
        ),
        laneCount = options.laneCount,
        observer = observer,
        keySelector = { append: ElasticsearchEventStreamAppend ->
            append.eventStream.aggregateId
        },
        writer = BatchWriter(
            ElasticsearchEventStreamBatchWriter(
                elasticsearchClient = elasticsearchClient,
                refreshPolicy = refreshPolicy,
            )::write
        ),
    )

    override fun append(eventStream: DomainEventStream): Mono<Void> {
        return coordinator.submit {
            ElasticsearchEventStreamAppend(
                eventStream = eventStream,
                index = eventStream.aggregateId.toEventStreamIndexName(),
                id = eventStream.toDocId(),
                document = eventStream.toLinkedHashMap(),
                routing = eventStream.aggregateId.id,
            )
        }.onErrorMap(::toElasticsearchBatchError)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        try {
            coordinator.close(closeTimeout)
        } catch (error: Throwable) {
            throw toElasticsearchBatchError(error)
        }
    }

    private fun toElasticsearchBatchError(error: Throwable): Throwable {
        return when (error) {
            is BatchOverflowException ->
                ElasticsearchEventStoreBatchOverflowException(options.maxPendingAppends)

            is BatchClosedException ->
                IllegalStateException("ElasticsearchEventStore is closed.")

            is BatchCloseTimeoutException -> mapCloseTimeout(error)

            else -> error
        }
    }

    private fun mapCloseTimeout(
        error: BatchCloseTimeoutException,
    ): ElasticsearchEventStoreBatchCloseTimeoutException {
        while (true) {
            val current = mappedCloseTimeout.get()
            if (current?.source === error) {
                return current.mapped
            }
            val mapped = MappedCloseTimeout(
                source = error,
                mapped = ElasticsearchEventStoreBatchCloseTimeoutException(error.timeout),
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
