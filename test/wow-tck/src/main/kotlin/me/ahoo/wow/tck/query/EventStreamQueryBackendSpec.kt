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
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
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
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.metrics.meteredForTck
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode

abstract class EventStreamQueryBackendSpec {
    val namedAggregate = MaterializedNamedAggregate("tck", "event-stream-query-spec")
    lateinit var eventStore: EventStore
    lateinit var eventStreamQueryBackendFactory: EventStreamQueryBackendFactory
    lateinit var eventStreamQueryBackend: EventStreamQueryBackend

    @BeforeEach
    open fun setup() {
        eventStore = createEventStore().meteredForTck()
        eventStreamQueryBackendFactory = createEventStreamQueryBackendFactory()
        eventStreamQueryBackend = eventStreamQueryBackendFactory.create(namedAggregate)
    }

    protected abstract fun createEventStore(): EventStore
    protected abstract fun createEventStreamQueryBackendFactory(): EventStreamQueryBackendFactory

    @Test
    fun createFromCache() {
        val queryService1 = eventStreamQueryBackendFactory.create(namedAggregate)
        val queryService2 = eventStreamQueryBackendFactory.create(namedAggregate)
        queryService1.assert().isSameAs(queryService2)
    }

    @Test
    fun `single should expose canonical event stream json`() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()
        singleQuery {
            condition {
                tenantId(eventStream.aggregateId.tenantId)
            }
        }.query(eventStreamQueryBackend)
            .test()
            .assertNext { node ->
                node.path("id").textValue().assert().isEqualTo(eventStream.id)
                node.path("body").isArray.assert().isTrue()
            }
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
        }.dynamicQuery(eventStreamQueryBackend)
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
        }.query(eventStreamQueryBackend)
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
        }.dynamicQuery(eventStreamQueryBackend)
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
        }.query(eventStreamQueryBackend)
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
        }.dynamicQuery(eventStreamQueryBackend)
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
        }.count(eventStreamQueryBackend)
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
        }.query(eventStreamQueryBackend)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map { it.path("count").longValue() }.sorted().assert().containsExactly(1L, 9L)
            }
            .verifyComplete()
    }

    @Test
    fun aggregateEmptySummary() {
        eventStore.append(generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))).block()

        aggregation {
            filter { tenantId(generateGlobalId()) }
            count("count")
        }.query(eventStreamQueryBackend)
            .test()
            .assertNext { it.path("count").longValue().assert().isZero() }
            .verifyComplete()
    }
}

private fun ISingleQuery.query(backend: EventStreamQueryBackend): Mono<ObjectNode> = backend.single(this)
private fun ISingleQuery.dynamicQuery(backend: EventStreamQueryBackend): Mono<ObjectNode> = backend.single(this)
private fun IListQuery.query(backend: EventStreamQueryBackend): Flux<ObjectNode> = backend.list(this)
private fun IListQuery.dynamicQuery(backend: EventStreamQueryBackend): Flux<ObjectNode> = backend.list(this)
private fun IPagedQuery.query(backend: EventStreamQueryBackend) = backend.paged(this)
private fun IPagedQuery.dynamicQuery(backend: EventStreamQueryBackend) = backend.paged(this)
private fun FilterExpression.count(backend: EventStreamQueryBackend): Mono<Long> = backend.count(this)
private fun AggregationQuery.query(backend: EventStreamQueryBackend): Flux<ObjectNode> = backend.aggregate(this)
