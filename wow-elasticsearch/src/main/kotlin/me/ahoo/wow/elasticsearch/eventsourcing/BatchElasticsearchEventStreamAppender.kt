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
import me.ahoo.wow.infra.batch.ReactiveBatchCloseTimeoutException
import me.ahoo.wow.infra.batch.ReactiveBatchClosedException
import me.ahoo.wow.infra.batch.ReactiveBatchCoordinator
import me.ahoo.wow.infra.batch.ReactiveBatchOptions
import me.ahoo.wow.infra.batch.ReactiveBatchOverflowException
import me.ahoo.wow.infra.batch.ReactiveBatchWriter
import me.ahoo.wow.serialization.toLinkedHashMap
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

internal class BatchElasticsearchEventStreamAppender(
    elasticsearchClient: ReactiveElasticsearchClient,
    refreshPolicy: Refresh,
    private val options: ElasticsearchEventStoreBatchOptions,
    private val closeTimeout: Duration = DEFAULT_CLOSE_TIMEOUT,
) : ElasticsearchEventStreamAppender {
    private data class MappedCloseTimeout(
        val source: ReactiveBatchCloseTimeoutException,
        val mapped: ElasticsearchEventStoreBatchCloseTimeoutException,
    )

    init {
        require(!closeTimeout.isNegative && !closeTimeout.isZero) {
            "closeTimeout must be positive."
        }
    }

    private val mappedCloseTimeout = AtomicReference<MappedCloseTimeout?>()
    private val coordinator = ReactiveBatchCoordinator(
        name = ElasticsearchEventStore::class.simpleName!!,
        options = ReactiveBatchOptions(
            maxSize = options.maxSize,
            maxDelay = options.maxDelay,
            maxPendingItems = options.maxPendingAppends,
        ),
        writer = ReactiveBatchWriter(
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
            is ReactiveBatchOverflowException ->
                ElasticsearchEventStoreBatchOverflowException(options.maxPendingAppends)

            is ReactiveBatchClosedException ->
                IllegalStateException("ElasticsearchEventStore is closed.")

            is ReactiveBatchCloseTimeoutException -> mapCloseTimeout(error)

            else -> error
        }
    }

    private fun mapCloseTimeout(
        error: ReactiveBatchCloseTimeoutException,
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
