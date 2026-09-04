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

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch.core.search.Hit
import co.elastic.clients.json.JsonData
import jakarta.json.JsonString
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortCompiler
import me.ahoo.wow.elasticsearch.query.event.EventStreamFilterCompiler
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AbstractEventStore
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.dsl.sort
import me.ahoo.wow.serialization.MessageRecords
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class ElasticsearchEventStore(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    val batchOptions: ElasticsearchEventStoreBatchOptions = ElasticsearchEventStoreBatchOptions(),
    private val refreshPolicy: Refresh = Refresh.True,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    metrics: WowMetrics = WowMetrics.NONE,
) : AbstractEventStore() {
    private val appender: ElasticsearchEventStreamAppender = if (batchOptions.enabled) {
        BatchElasticsearchEventStreamAppender(
            elasticsearchClient = elasticsearchClient,
            refreshPolicy = refreshPolicy,
            options = batchOptions,
            metrics = metrics,
        )
    } else {
        DirectElasticsearchEventStreamAppender(
            elasticsearchClient = elasticsearchClient,
            refreshPolicy = refreshPolicy,
        )
    }

    companion object {
        private const val NOT_FOUND_CODE = 404
        private const val DEFAULT_BATCH_SIZE = 10000
        private const val MAX_EVENT_STREAM_SIZE = 10000
    }

    init {
        require(batchSize > 0) { "batchSize[$batchSize] must be greater than 0." }
    }

    private data class EventStreamPage(
        val streams: List<DomainEventStream>,
        val nextSearchAfter: List<FieldValue>?,
    )

    override fun appendStream(eventStream: DomainEventStream): Mono<Void> {
        return Mono.defer {
            require(eventStream.size <= MAX_EVENT_STREAM_SIZE) {
                "eventStream.size[${eventStream.size}] must not exceed Elasticsearch nested object limit" +
                    "[$MAX_EVENT_STREAM_SIZE]."
            }
            appender.append(eventStream)
        }
    }

    override fun loadStream(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int
    ): Flux<DomainEventStream> {
        val filter =
            filter {
                tenantId(aggregateId.tenantId)
                aggregateId(aggregateId.id)
                MessageRecords.VERSION.between(headVersion, tailVersion)
            }
        return searchEventStreams(aggregateId, filter)
    }

    override fun loadStream(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long
    ): Flux<DomainEventStream> {
        val filter =
            filter {
                tenantId(aggregateId.tenantId)
                aggregateId(aggregateId.id)
                MessageRecords.CREATE_TIME.between(headEventTime, tailEventTime)
            }
        return searchEventStreams(aggregateId, filter)
    }

    private fun searchEventStreams(
        aggregateId: AggregateId,
        filter: FilterExpression,
    ): Flux<DomainEventStream> {
        return searchEventStreamPage(aggregateId, filter)
            .expand { page ->
                page.nextSearchAfter?.let { searchAfter ->
                    searchEventStreamPage(aggregateId, filter, searchAfter)
                } ?: Mono.empty()
            }
            .concatMapIterable { it.streams }
    }

    private fun searchEventStreamPage(
        aggregateId: AggregateId,
        filter: FilterExpression,
        searchAfter: List<FieldValue> = emptyList(),
    ): Mono<EventStreamPage> {
        return searchEventStreamHits(
            aggregateId = aggregateId,
            filter = filter,
            size = batchSize,
            searchAfter = searchAfter,
        ).map { hits ->
            val streams = hits.map { hit -> requireNotNull(hit.source()) }
            val nextSearchAfter = if (hits.size < batchSize) {
                null
            } else {
                hits.last().sort().also {
                    check(it.isNotEmpty()) { "Elasticsearch search_after cursor must not be empty." }
                }
            }
            EventStreamPage(streams, nextSearchAfter)
        }
    }

    private fun searchEventStreamHits(
        aggregateId: AggregateId,
        filter: FilterExpression,
        size: Int,
        searchAfter: List<FieldValue> = emptyList(),
        descending: Boolean = false,
    ): Mono<List<Hit<DomainEventStream>>> {
        val query = EventStreamFilterCompiler.compilePhysical(filter)
        val sort = ElasticsearchSortCompiler.compilePhysical(
            sort {
                if (descending) {
                    MessageRecords.VERSION.desc()
                    MessageRecords.ID.desc()
                } else {
                    MessageRecords.VERSION.asc()
                    MessageRecords.ID.asc()
                }
            },
        )
        return elasticsearchClient
            .search({ request ->
                request
                    .index(aggregateId.toEventStreamIndexName())
                    .query(query)
                    .size(size)
                    .routing(aggregateId.id)
                    .sort(sort)
                if (searchAfter.isNotEmpty()) {
                    request.searchAfter(searchAfter)
                }
                request
            }, DomainEventStream::class.java)
            .map {
                it.hits().hits()
            }
            .onErrorResume(::missingIndexAsEmpty)
    }

    override fun last(aggregateId: AggregateId): Mono<DomainEventStream> {
        val filter =
            filter {
                tenantId(aggregateId.tenantId)
                aggregateId(aggregateId.id)
            }
        return searchEventStreamHits(
            aggregateId = aggregateId,
            filter = filter,
            size = 1,
            descending = true,
        )
            .mapNotNull {
                it
                    .firstOrNull()
                    ?.source()
            }
    }

    override fun scanAggregateId(
        namedAggregate: NamedAggregate,
        afterId: String,
        limit: Int
    ): Flux<AggregateId> {
        val filter = filter {
            MessageRecords.AGGREGATE_ID gt afterId
            MessageRecords.VERSION eq Version.INITIAL_VERSION
        }
        val sort = ElasticsearchSortCompiler.compilePhysical(sort { MessageRecords.AGGREGATE_ID.asc() })
        return elasticsearchClient
            .search({
                it
                    .index(namedAggregate.toEventStreamIndexName())
                    .query(EventStreamFilterCompiler.compilePhysical(filter))
                    .source { sourceBuilder -> sourceBuilder.fetch(false) }
                    .docvalueFields { field -> field.field(MessageRecords.AGGREGATE_ID) }
                    .docvalueFields { field -> field.field(MessageRecords.TENANT_ID) }
                    .size(limit)
                    .sort(sort)
            }, Map::class.java)
            .onErrorResume(::missingIndexAsEmpty)
            .flatMapIterable<AggregateId> {
                it.hits().hits().map { hit ->
                    val aggregateId = hit.fields().requiredStringDocValue(MessageRecords.AGGREGATE_ID)
                    val tenantId = hit.fields().requiredStringDocValue(MessageRecords.TENANT_ID)
                    namedAggregate.aggregateId(aggregateId, tenantId)
                }
            }
    }

    private fun <T : Any> missingIndexAsEmpty(error: Throwable): Mono<T> {
        if (error is ElasticsearchException && error.status() == NOT_FOUND_CODE) {
            return Mono.empty()
        }
        return Mono.error(error)
    }

    override fun close() {
        appender.close()
    }
}

private fun Map<String, JsonData>.requiredStringDocValue(field: String): String {
    val value = checkNotNull(this[field])
        .toJson()
        .asJsonArray()
        .single()
    return checkNotNull(value as? JsonString).string
}
