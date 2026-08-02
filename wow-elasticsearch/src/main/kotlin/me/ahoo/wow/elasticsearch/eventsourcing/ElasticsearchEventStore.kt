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
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortConverter.toSortOptions
import me.ahoo.wow.elasticsearch.query.event.EventStreamConditionConverter
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AbstractEventStore
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.sort
import me.ahoo.wow.serialization.MessageRecords
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class ElasticsearchEventStore private constructor(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val refreshPolicy: Refresh = Refresh.True,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    val batchOptions: ElasticsearchEventStoreBatchOptions,
    private val appender: ElasticsearchEventStreamAppender,
) : AbstractEventStore() {
    constructor(
        elasticsearchClient: ReactiveElasticsearchClient,
        refreshPolicy: Refresh = Refresh.True,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ) : this(
        elasticsearchClient = elasticsearchClient,
        refreshPolicy = refreshPolicy,
        batchSize = batchSize,
        batchOptions = ElasticsearchEventStoreBatchOptions(),
        appender = DirectElasticsearchEventStreamAppender(
            elasticsearchClient = elasticsearchClient,
            refreshPolicy = refreshPolicy,
        ),
    )

    constructor(
        elasticsearchClient: ReactiveElasticsearchClient,
        batchOptions: ElasticsearchEventStoreBatchOptions,
        refreshPolicy: Refresh = Refresh.True,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ) : this(
        elasticsearchClient = elasticsearchClient,
        refreshPolicy = refreshPolicy,
        batchSize = batchSize,
        batchOptions = batchOptions,
        appender = if (batchOptions.enabled) {
            BatchElasticsearchEventStreamAppender(
                elasticsearchClient = elasticsearchClient,
                refreshPolicy = refreshPolicy,
                options = batchOptions,
            )
        } else {
            DirectElasticsearchEventStreamAppender(
                elasticsearchClient = elasticsearchClient,
                refreshPolicy = refreshPolicy,
            )
        },
    )

    companion object {
        private const val NOT_FOUND_CODE = 404
        private const val DEFAULT_BATCH_SIZE = 10000
    }

    init {
        require(batchSize > 0) { "batchSize[$batchSize] must be greater than 0." }
    }

    private data class EventStreamPage(
        val streams: List<DomainEventStream>,
        val nextSearchAfter: List<FieldValue>?,
    )

    override fun appendStream(eventStream: DomainEventStream): Mono<Void> {
        return appender.append(eventStream)
    }

    override fun loadStream(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int
    ): Flux<DomainEventStream> {
        val condition =
            condition {
                tenantId(aggregateId.tenantId)
                MessageRecords.AGGREGATE_ID eq aggregateId.id
                MessageRecords.VERSION between headVersion to tailVersion
            }
        return searchEventStreams(aggregateId, condition)
    }

    override fun loadStream(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long
    ): Flux<DomainEventStream> {
        val condition =
            condition {
                tenantId(aggregateId.tenantId)
                MessageRecords.AGGREGATE_ID eq aggregateId.id
                MessageRecords.CREATE_TIME between headEventTime to tailEventTime
            }
        return searchEventStreams(aggregateId, condition)
    }

    private fun searchEventStreams(
        aggregateId: AggregateId,
        condition: Condition,
    ): Flux<DomainEventStream> {
        return searchEventStreamPage(aggregateId, condition)
            .expand { page ->
                page.nextSearchAfter?.let { searchAfter ->
                    searchEventStreamPage(aggregateId, condition, searchAfter)
                } ?: Mono.empty()
            }
            .concatMapIterable { it.streams }
    }

    private fun searchEventStreamPage(
        aggregateId: AggregateId,
        condition: Condition,
        searchAfter: List<FieldValue> = emptyList(),
    ): Mono<EventStreamPage> {
        return searchEventStreamHits(
            aggregateId = aggregateId,
            condition = condition,
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
        condition: Condition,
        size: Int,
        searchAfter: List<FieldValue> = emptyList(),
        descending: Boolean = false,
    ): Mono<List<Hit<DomainEventStream>>> {
        val query = EventStreamConditionConverter.convert(condition)
        val sort = sort {
            if (descending) {
                MessageRecords.VERSION.desc()
                MessageRecords.ID.desc()
            } else {
                MessageRecords.VERSION.asc()
                MessageRecords.ID.asc()
            }
        }.toSortOptions()
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
        val condition =
            condition {
                tenantId(aggregateId.tenantId)
                MessageRecords.AGGREGATE_ID eq aggregateId.id
            }
        return searchEventStreamHits(
            aggregateId = aggregateId,
            condition = condition,
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
        val condition = condition {
            MessageRecords.AGGREGATE_ID gt afterId
            MessageRecords.VERSION eq Version.INITIAL_VERSION
        }
        val sort = sort { MessageRecords.AGGREGATE_ID.asc() }.toSortOptions()
        return elasticsearchClient
            .search({
                it
                    .index(namedAggregate.toEventStreamIndexName())
                    .query(EventStreamConditionConverter.convert(condition))
                    .source { sourceBuilder ->
                        sourceBuilder.filter { sourceFilterBuilder ->
                            sourceFilterBuilder.includes(MessageRecords.AGGREGATE_ID, MessageRecords.TENANT_ID)
                        }
                    }
                    .size(limit)
                    .sort(sort)
            }, Map::class.java)
            .onErrorResume(::missingIndexAsEmpty)
            .flatMapIterable<AggregateId> {
                it.hits().hits().map { hit ->
                    val source = requireNotNull(hit.source())
                    val aggregateId = checkNotNull(source[MessageRecords.AGGREGATE_ID] as String)
                    val tenantId = checkNotNull(source[MessageRecords.TENANT_ID] as String)
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
