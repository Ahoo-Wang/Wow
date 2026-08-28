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

package me.ahoo.wow.tck.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.count
import me.ahoo.wow.query.event.dynamicQuery
import me.ahoo.wow.query.event.query
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.metrics.meteredForTck
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

abstract class EventStreamQueryServiceSpec {
    val namedAggregate = MaterializedNamedAggregate("tck", "event-stream-query-spec")
    lateinit var eventStore: EventStore
    lateinit var eventStreamQueryServiceFactory: EventStreamQueryServiceFactory
    lateinit var eventStreamQueryService: EventStreamQueryService

    @BeforeEach
    open fun setup() {
        eventStore = createEventStore().meteredForTck()
        eventStreamQueryServiceFactory = createEventStreamQueryServiceFactory()
        eventStreamQueryService = eventStreamQueryServiceFactory.create(namedAggregate)
    }

    protected abstract fun createEventStore(): EventStore
    protected abstract fun createEventStreamQueryServiceFactory(): EventStreamQueryServiceFactory

    @Test
    fun createFromCache() {
        val queryService1 = eventStreamQueryServiceFactory.create(namedAggregate)
        val queryService2 = eventStreamQueryServiceFactory.create(namedAggregate)
        queryService1.assert().isSameAs(queryService2)
    }

    @Test
    fun single() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()
        singleQuery {
            condition {
                tenantId(eventStream.aggregateId.tenantId)
            }
        }.query(eventStreamQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun dynamicSingle() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()
        singleQuery {
            condition {
                tenantId(eventStream.aggregateId.tenantId)
            }
        }.dynamicQuery(eventStreamQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun list() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()
        listQuery {
            condition {
                tenantId(eventStream.aggregateId.tenantId)
            }
        }.query(eventStreamQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun dynamicList() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()
        listQuery {
            condition {
                tenantId(eventStream.aggregateId.tenantId)
            }
        }.dynamicQuery(eventStreamQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun paged() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()
        pagedQuery {
            condition {
                tenantId(eventStream.aggregateId.tenantId)
            }
        }.query(eventStreamQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun dynamicPaged() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()
        pagedQuery {
            condition {
                tenantId(eventStream.aggregateId.tenantId)
            }
        }.dynamicQuery(eventStreamQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun count() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()
        filterExpression {
            tenantId(eventStream.aggregateId.tenantId)
        }.count(eventStreamQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun aggregateEventsByName() {
        val tenantId = generateGlobalId()
        eventStore.append(generateEventStream(namedAggregate.aggregateId(tenantId = tenantId))).block()

        aggregation {
            filter { tenantId(tenantId) }
            expand("body")
            terms("name", "eventName")
            count("count")
        }.query(eventStreamQueryService)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map { it.getValue<Long>("count") }.sorted().assert().containsExactly(1L, 9L)
            }
            .verifyComplete()
    }

    @Test
    fun aggregateEmptySummary() {
        eventStore.append(generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))).block()

        aggregation {
            filter { tenantId(generateGlobalId()) }
            count("count")
        }.query(eventStreamQueryService)
            .test()
            .assertNext { it.getValue<Long>("count").assert().isZero() }
            .verifyComplete()
    }
}
