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

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.count
import me.ahoo.wow.query.event.dynamicQuery
import me.ahoo.wow.query.event.query
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import org.hamcrest.CoreMatchers.sameInstance
import org.hamcrest.MatcherAssert.*
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
        eventStore = createEventStore().metrizable()
        eventStreamQueryServiceFactory = createEventStreamQueryServiceFactory()
        eventStreamQueryService = eventStreamQueryServiceFactory.create(namedAggregate)
    }

    protected abstract fun createEventStore(): EventStore
    protected abstract fun createEventStreamQueryServiceFactory(): EventStreamQueryServiceFactory

    @Test
    fun createFromCache() {
        val queryService1 = eventStreamQueryServiceFactory.create(namedAggregate)
        val queryService2 = eventStreamQueryServiceFactory.create(namedAggregate)
        assertThat(queryService1, sameInstance(queryService2))
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
    fun count() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()
        condition {
            tenantId(eventStream.aggregateId.tenantId)
        }.count(eventStreamQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }
}
