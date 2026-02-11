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
import co.elastic.clients.elasticsearch._types.OpType
import co.elastic.clients.elasticsearch._types.Refresh
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortConverter.toSortOptions
import me.ahoo.wow.elasticsearch.query.event.EventStreamConditionConverter
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AbstractEventStore
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.sort
import me.ahoo.wow.serialization.MessageRecords
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class ElasticsearchEventStore(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val refreshPolicy: Refresh = Refresh.True,
    private val batchSize: Int = DEFAULT_BATCH_SIZE
) : AbstractEventStore() {
    companion object {
        private const val VERSION_CONFLICT_CODE = 409
        private const val DEFAULT_BATCH_SIZE = 10000
    }

    private fun DomainEventStream.toDocId(): String = "${this.aggregateId.id}-${this.version}"

    override fun appendStream(eventStream: DomainEventStream): Mono<Void> {
        return elasticsearchClient
            .index {
                it
                    .index(eventStream.aggregateId.toEventStreamIndexName())
                    .id(eventStream.toDocId())
                    .document(eventStream)
                    .routing(eventStream.aggregateId.id)
                    .opType(OpType.Create)
                    .refresh(refreshPolicy)
            }.onErrorResume {
                if (it is ElasticsearchException && it.status() == VERSION_CONFLICT_CODE) {
                    return@onErrorResume EventVersionConflictException(
                        eventStream = eventStream,
                        cause = it,
                    ).toMono()
                }
                Mono.error(it)
            }.then()
    }

    override fun loadStream(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int
    ): Flux<DomainEventStream> = loopStream(aggregateId, headVersion, tailVersion)

    private fun loopStream(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int
    ): Flux<DomainEventStream> {
        var endVersion = headVersion + batchSize - 1
        if (tailVersion < endVersion) {
            endVersion = tailVersion
        }
        return findEventStream(aggregateId, headVersion, endVersion).flatMapMany {
            val previousStreams = Flux.fromIterable(it)
            val requestSize = endVersion - headVersion + 1
            if (it.size < requestSize) {
                return@flatMapMany previousStreams
            }
            val lastVersion = it.last().version
            if (lastVersion >= tailVersion) {
                return@flatMapMany previousStreams
            }
            val nextStreams = loopStream(aggregateId, lastVersion + 1, tailVersion)
            return@flatMapMany previousStreams.concatWith(nextStreams)
        }
    }

    private fun findEventStream(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int
    ): Mono<List<DomainEventStream>> {
        val condition =
            condition {
                tenantId(aggregateId.tenantId)
                MessageRecords.AGGREGATE_ID eq aggregateId.id
                MessageRecords.VERSION between headVersion to tailVersion
            }
        val size = tailVersion - headVersion + 1
        return searchEventStream(aggregateId, condition, size)
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
        return searchEventStream(aggregateId, condition).flatMapIterable {
            it
        }
    }

    private fun searchEventStream(
        aggregateId: AggregateId,
        condition: Condition,
        size: Int = batchSize
    ): Mono<List<DomainEventStream>> {
        val query = EventStreamConditionConverter.convert(condition)
        val sort = sort { MessageRecords.VERSION.asc() }.toSortOptions()
        return elasticsearchClient
            .search({
                it
                    .index(aggregateId.toEventStreamIndexName())
                    .query(query)
                    .size(size)
                    .routing(aggregateId.id)
                    .sort(sort)
            }, DomainEventStream::class.java)
            .map {
                it.hits().hits().map { hit -> hit.source() as DomainEventStream }
            }
    }

    override fun last(aggregateId: AggregateId): Mono<DomainEventStream> {
        val condition =
            condition {
                tenantId(aggregateId.tenantId)
                MessageRecords.AGGREGATE_ID eq aggregateId.id
            }
        val sort = sort { MessageRecords.VERSION.desc() }.toSortOptions()
        return elasticsearchClient
            .search({
                it
                    .index(aggregateId.toEventStreamIndexName())
                    .query(EventStreamConditionConverter.convert(condition))
                    .size(1)
                    .routing(aggregateId.id)
                    .sort(sort)
            }, DomainEventStream::class.java)
            .mapNotNull {
                it
                    .hits()
                    .hits()
                    .firstOrNull()
                    ?.source()
            }
    }
}
