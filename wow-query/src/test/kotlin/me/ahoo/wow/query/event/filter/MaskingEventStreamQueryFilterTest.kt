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

package me.ahoo.wow.query.event.filter

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.mask.EventStreamDynamicDocumentMasker
import me.ahoo.wow.query.mask.EventStreamMaskerRegistry
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class MaskingEventStreamQueryFilterTest {
    private val eventStreamMaskerRegistry = EventStreamMaskerRegistry()
    private val tailSnapshotQueryFilter = TailEventStreamQueryFilter(MockEventStreamQueryServiceFactory)
    private val queryFilterChain = FilterChainBuilder<EventStreamQueryContext<*, *, *>>()
        .addFilters(listOf(tailSnapshotQueryFilter, MaskingEventStreamQueryFilter(eventStreamMaskerRegistry)))
        .filterCondition(EventStreamQueryHandler::class)
        .build()
    private val queryHandler = DefaultEventStreamQueryHandler(
        queryFilterChain,
        LogErrorHandler()
    )

    init {
        eventStreamMaskerRegistry.register(MockEventStreamMasker)
    }

    @Test
    fun filter() {
        val query = listQuery { }
        queryHandler.dynamicList(MOCK_AGGREGATE_METADATA, query)
            .test()
            .consumeNextWith {
                assertThat(it.containsKey(MessageRecords.CONTEXT_NAME), equalTo(false))
            }
            .verifyComplete()
    }
}

object MockEventStreamMasker : EventStreamDynamicDocumentMasker {
    override val namedAggregate: NamedAggregate
        get() = MockEventStreamQueryService.namedAggregate

    override fun mask(dynamicDocument: DynamicDocument): DynamicDocument {
        dynamicDocument.remove(MessageRecords.CONTEXT_NAME)
        return dynamicDocument
    }
}

object MockEventStreamQueryServiceFactory : EventStreamQueryServiceFactory {
    override fun create(namedAggregate: NamedAggregate): EventStreamQueryService {
        return MockEventStreamQueryService
    }
}

object MockEventStreamQueryService : EventStreamQueryService {
    override val namedAggregate: NamedAggregate
        get() = MOCK_AGGREGATE_METADATA
    private val eventStream = generateEventStream(MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId()))

    private val dynamicDocument = eventStream.toJsonString().toObject<MutableMap<String, Any>>().toDynamicDocument()
    override fun list(listQuery: IListQuery): Flux<DomainEventStream> {
        return Flux.just(eventStream)
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        return Flux.just(dynamicDocument)
    }

    override fun count(condition: Condition): Mono<Long> {
        return 1L.toMono()
    }
}
