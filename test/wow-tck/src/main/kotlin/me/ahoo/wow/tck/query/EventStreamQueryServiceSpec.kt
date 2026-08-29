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
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.SimpleDomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
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
    fun `cursor should traverse first middle and last pages with a stable tie breaker`() {
        val streams = (1..5).map { saveCursorEventStream("cursor-event-$it", aggregateVersion = 1) }
        val query = CursorQuery(
            filter = AggregateIdsFilter(streams.map { it.aggregateId.id }),
            sort = listOf(Sort("version", Sort.Direction.ASC)),
            size = 2,
        )
        val first = query.query(eventStreamQueryService).block()!!
        val middle = query.copy(cursor = first.nextCursor).query(eventStreamQueryService).block()!!
        val last = query.copy(cursor = middle.nextCursor).query(eventStreamQueryService).block()!!
        val expected = streams.map(DomainEventStream::id).sorted()

        first.list.map(DomainEventStream::id).assert().containsExactly(expected[0], expected[1])
        middle.list.map(DomainEventStream::id).assert().containsExactly(expected[2], expected[3])
        last.list.map(DomainEventStream::id).assert().containsExactly(expected[4])
        first.nextCursor.assert().isNotNull()
        middle.nextCursor.assert().isNotNull()
        last.nextCursor.assert().isNull()
    }

    @Test
    fun `cursor should use the unique field as the default sort`() {
        val streams = listOf("cursor-default-c", "cursor-default-a", "cursor-default-b")
            .map { saveCursorEventStream(it, aggregateVersion = 1) }
        val query = CursorQuery(AggregateIdsFilter(streams.map { it.aggregateId.id }), size = 2)
        val first = query.query(eventStreamQueryService).block()!!
        val second = query.copy(cursor = first.nextCursor).query(eventStreamQueryService).block()!!

        (first.list + second.list).map(DomainEventStream::id).assert()
            .containsExactly(*streams.map(DomainEventStream::id).sorted().toTypedArray())
        second.nextCursor.assert().isNull()
    }

    @Test
    fun `cursor should preserve mixed sort directions across pages`() {
        val streams = listOf(
            saveCursorEventStream("cursor-mixed-a", aggregateVersion = 1),
            saveCursorEventStream("cursor-mixed-b", aggregateVersion = 1),
            saveCursorEventStream("cursor-mixed-c", aggregateVersion = 2),
        )
        val query = CursorQuery(
            filter = AggregateIdsFilter(streams.map { it.aggregateId.id }),
            sort = listOf(
                Sort("version", Sort.Direction.DESC),
                Sort("aggregateId", Sort.Direction.ASC),
            ),
            size = 2,
        )
        val first = query.query(eventStreamQueryService).block()!!
        val second = query.copy(cursor = first.nextCursor).query(eventStreamQueryService).block()!!
        val expected = streams.sortedWith(
            compareByDescending<DomainEventStream> { it.version }.thenBy { it.aggregateId.id },
        )

        (first.list + second.list).map { it.aggregateId.id }.assert()
            .containsExactly(*expected.map { it.aggregateId.id }.toTypedArray())
    }

    @Test
    fun `cursor should continue after null and missing sort values`() {
        val streams = listOf(
            saveCursorEventStream("cursor-null-a"),
            saveCursorEventStream("cursor-null-b", cursorOrder = null, includeCursorOrder = true),
            saveCursorEventStream("cursor-null-c", cursorOrder = 1, includeCursorOrder = true),
        )
        val query = CursorQuery(
            filter = AggregateIdsFilter(streams.map { it.aggregateId.id }),
            sort = listOf(Sort("header.cursorOrder", Sort.Direction.ASC)),
            size = 2,
        )
        val first = query.dynamicQuery(eventStreamQueryService).block()!!
        val second = query.copy(cursor = first.nextCursor).dynamicQuery(eventStreamQueryService).block()!!
        val documents = first.list + second.list
        val expected = streams.take(2).map(DomainEventStream::id).sorted() + streams[2].id
        val byId = documents.associateBy { it["id"] }

        documents.map { it["id"] }.assert().containsExactly(*expected.toTypedArray())
        byId.getValue(streams[0].id).getNestedDocument("header").containsKey("cursorOrder").assert().isFalse()
        val explicitNullHeader = byId.getValue(streams[1].id).getNestedDocument("header")
        explicitNullHeader.containsKey("cursorOrder").assert().isTrue()
        explicitNullHeader["cursorOrder"].assert().isNull()
        byId.getValue(streams[2].id).getNestedDocument("header")["cursorOrder"].assert().isEqualTo("1")
        second.nextCursor.assert().isNull()
    }

    @Test
    fun `dynamic cursor should exclude projected sort fields without breaking continuation`() {
        val streams = listOf("cursor-projection-a", "cursor-projection-b", "cursor-projection-c")
            .map { saveCursorEventStream(it, aggregateVersion = 1) }
        val query = CursorQuery(
            filter = AggregateIdsFilter(streams.map { it.aggregateId.id }),
            projection = Projection(exclude = listOf("version")),
            sort = listOf(Sort("version", Sort.Direction.ASC)),
            size = 2,
        )
        val first = query.dynamicQuery(eventStreamQueryService).block()!!
        val second = query.copy(cursor = first.nextCursor).dynamicQuery(eventStreamQueryService).block()!!

        (first.list + second.list).map { it.containsKey("version") }.assert().containsExactly(false, false, false)
        (first.list + second.list).map { it["id"] }.toSet().assert()
            .isEqualTo(streams.map(DomainEventStream::id).toSet())
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

    private fun saveCursorEventStream(
        aggregateId: String,
        aggregateVersion: Int = 1,
        cursorOrder: Int? = null,
        includeCursorOrder: Boolean = false,
    ): DomainEventStream {
        val generated = generateEventStream(
            aggregateId = namedAggregate.aggregateId(id = aggregateId),
            aggregateVersion = aggregateVersion,
            eventCount = 1,
        )
        val eventStream = if (includeCursorOrder) {
            SimpleDomainEventStream(
                id = generated.id,
                requestId = generated.requestId,
                header = nullableCursorHeader(cursorOrder),
                body = generated.body,
            )
        } else {
            generated
        }
        eventStore.append(eventStream).test().verifyComplete()
        return eventStream
    }

    @Suppress("UNCHECKED_CAST")
    private fun nullableCursorHeader(cursorOrder: Int?): DefaultHeader = DefaultHeader(
        mutableMapOf<String, Any?>("cursorOrder" to cursorOrder?.toString()) as MutableMap<String, String>,
    )
}
