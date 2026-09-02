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
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.ResolvedQuery
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryBackend
import me.ahoo.wow.query.event.requiredQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.requireAccepted
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.metrics.meteredForTck
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.atomic.AtomicInteger

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
    protected abstract fun prepareNullAndMissingCursorEventStreams(
        nullStream: DomainEventStream,
        missingStream: DomainEventStream,
    )

    @Test
    fun createFromCache() {
        val backend1 = eventStreamQueryBackendFactory.create(namedAggregate)
        val backend2 = eventStreamQueryBackendFactory.create(namedAggregate)
        backend1.assert().isSameAs(backend2)
    }

    @Test
    fun `query helpers defer schema lookup until subscription`() {
        val schemaCalls = AtomicInteger()
        val backend = object :
            EventStreamQueryBackend by NoOpEventStreamQueryBackend(namedAggregate),
            QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> {
                schemaCalls.incrementAndGet()
                return Mono.just(QueryModelSchema(QueryModel.EVENT_STREAM, emptySet(), emptyMap()))
            }

            override fun refresh(): Mono<QueryModelSchema> = schema()
        }

        val single = singleQuery { }.query(backend)
        val aggregate = aggregation { count("count") }.query(backend)

        schemaCalls.get().assert().isZero()
        single.thenMany(aggregate).test().verifyComplete()
        schemaCalls.get().assert().isEqualTo(2)
    }

    @Test
    fun `single should expose canonical event stream json`() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()
        singleQuery {
            filter {
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
            filter {
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
            filter {
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
            filter {
                tenantId(eventStream.aggregateId.tenantId)
            }
        }.dynamicQuery(eventStreamQueryBackend)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `payload projection should retain body type`() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()

        eventStreamQueryBackend.single(
            SingleQuery(
                filter = TenantIdFilter(eventStream.aggregateId.tenantId),
                projection = Projection(
                    include = listOf(QueryField("body.body"), QueryField("body.bodyType")),
                ),
            ),
        ).test()
            .assertNext { node ->
                node.path("body").path(0).let { event ->
                    event.path("body").isObject.assert().isTrue()
                    event.path("bodyType").asString().assert().isNotBlank()
                }
            }
            .verifyComplete()
    }

    @Test
    fun `payload projection without body type should be rejected`() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()

        eventStreamQueryBackend.single(
            SingleQuery(
                filter = TenantIdFilter(eventStream.aggregateId.tenantId),
                projection = Projection(include = listOf(QueryField("body.body"))),
            ),
        ).test()
            .expectError(QuerySchemaValidationException::class.java)
            .verify()
    }

    @Test
    fun `metadata-only projection should omit payload and body type`() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()

        eventStreamQueryBackend.single(
            SingleQuery(
                filter = TenantIdFilter(eventStream.aggregateId.tenantId),
                projection = Projection(include = listOf(QueryField("body.id"))),
            ),
        ).test()
            .assertNext { node ->
                node.path("body").path(0).let { event ->
                    event.path("id").asString().assert().isNotBlank()
                    event.has("body").assert().isFalse()
                    event.has("bodyType").assert().isFalse()
                }
            }
            .verifyComplete()
    }

    @Test
    fun `excluding payload and body type should retain event metadata`() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()

        eventStreamQueryBackend.single(
            SingleQuery(
                filter = TenantIdFilter(eventStream.aggregateId.tenantId),
                projection = Projection(
                    exclude = listOf(QueryField("body.body"), QueryField("body.bodyType")),
                ),
            ),
        ).test()
            .assertNext { node ->
                node.path("body").path(0).let { event ->
                    event.path("id").asString().assert().isNotBlank()
                    event.has("body").assert().isFalse()
                    event.has("bodyType").assert().isFalse()
                }
            }
            .verifyComplete()
    }

    @Test
    fun paged() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))
        eventStore.append(eventStream).block()
        pagedQuery {
            filter {
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
            filter {
                tenantId(eventStream.aggregateId.tenantId)
            }
        }.dynamicQuery(eventStreamQueryBackend)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `cursor should traverse tied versions without duplicates`() {
        val tenantId = generateGlobalId()
        repeat(3) {
            eventStore.append(generateEventStream(namedAggregate.aggregateId(tenantId = tenantId))).block()
        }
        val query = CursorQuery(
            TenantIdFilter(tenantId),
            sort = listOf(Sort(QueryField("version"), Sort.Direction.ASC)),
            size = 2,
        )

        val first = eventStreamQueryBackend.cursor(query).block()!!
        val second = eventStreamQueryBackend.cursor(query.copy(cursor = first.nextCursor)).block()!!

        first.list.assert().hasSize(2)
        first.nextCursor.assert().isNotNull()
        (first.list + second.list).map { it.path("id").textValue() }.distinct().assert().hasSize(3)
        second.list.assert().hasSize(1)
        second.nextCursor.assert().isNull()
    }

    @Test
    fun `cursor should support descending multi field sort`() {
        val tenantId = generateGlobalId()
        val highest = generateEventStream(namedAggregate.aggregateId(tenantId = tenantId), aggregateVersion = 2)
        val tied = List(2) {
            generateEventStream(namedAggregate.aggregateId(tenantId = tenantId), aggregateVersion = 1)
        }
        listOf(highest, *tied.toTypedArray()).forEach { eventStore.append(it).block() }
        val query = CursorQuery(
            TenantIdFilter(tenantId),
            sort = listOf(
                Sort(QueryField("version"), Sort.Direction.DESC),
                Sort(QueryField("id"), Sort.Direction.DESC),
            ),
            size = 2,
        )

        val first = eventStreamQueryBackend.cursor(query).block()!!
        val second = eventStreamQueryBackend.cursor(query.copy(cursor = first.nextCursor)).block()!!

        (first.list + second.list).map { it.path("id").textValue() }.assert().containsExactly(
            highest.id,
            *tied.sortedByDescending { it.id }.map { it.id }.toTypedArray(),
        )
        first.nextCursor.assert().isNotNull()
        second.nextCursor.assert().isNull()
    }

    @Test
    fun `cursor should traverse null and missing sort values in both directions`() {
        val tenantId = generateGlobalId()
        val nullStream = generateEventStream(namedAggregate.aggregateId(tenantId = tenantId), ownerId = "null")
        val missingStream = generateEventStream(namedAggregate.aggregateId(tenantId = tenantId), ownerId = "missing")
        val valueStream = generateEventStream(namedAggregate.aggregateId(tenantId = tenantId), ownerId = "value")
        listOf(nullStream, missingStream, valueStream).forEach { eventStore.append(it).block() }
        prepareNullAndMissingCursorEventStreams(nullStream, missingStream)
        val nullishIds = listOf(nullStream.id, missingStream.id).sorted()

        listOf(
            Sort.Direction.ASC to (nullishIds + valueStream.id),
            Sort.Direction.DESC to (listOf(valueStream.id) + nullishIds),
        ).forEach { (direction, expectedIds) ->
            val query = CursorQuery(
                TenantIdFilter(tenantId),
                sort = listOf(Sort(QueryField("ownerId"), direction)),
                size = 2,
            )

            val first = eventStreamQueryBackend.cursor(query).block()!!
            val second = eventStreamQueryBackend.cursor(query.copy(cursor = first.nextCursor)).block()!!
            val nodes = first.list + second.list

            nodes.map { it.path("id").textValue() }.assert().containsExactly(*expectedIds.toTypedArray())
            nodes.map { it.path("id").textValue() }.distinct().assert().hasSize(3)
            nodes.associateBy { it.path("id").textValue() }.let { byId ->
                byId.getValue(nullStream.id).path("ownerId").isNull.assert().isTrue()
                byId.getValue(missingStream.id).path("ownerId").isMissingNode.assert().isTrue()
            }
            first.nextCursor.assert().isNotNull()
            second.list.assert().hasSize(1)
            second.nextCursor.assert().isNull()
        }
    }

    @Test
    fun `cursor should not expose projection-only cursor fields`() {
        val tenantId = generateGlobalId()
        eventStore.append(generateEventStream(namedAggregate.aggregateId(tenantId = tenantId))).block()

        eventStreamQueryBackend.cursor(
            CursorQuery(
                TenantIdFilter(tenantId),
                projection = Projection(include = listOf(QueryField("tenantId"))),
                sort = listOf(Sort(QueryField("version"), Sort.Direction.ASC)),
            ),
        ).test()
            .assertNext { page ->
                page.list.single().let { node ->
                    node.path("tenantId").textValue().assert().isEqualTo(tenantId)
                    node.has("version").assert().isFalse()
                    node.has("id").assert().isFalse()
                }
            }.verifyComplete()
    }

    @Test
    fun `cursor should reject a malformed token`() {
        eventStore.append(generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))).block()

        eventStreamQueryBackend.cursor(
            CursorQuery(
                MatchAllFilter,
                sort = listOf(Sort(QueryField("version"), Sort.Direction.ASC)),
                cursor = "malformed",
            ),
        ).test()
            .expectErrorMessage("Invalid cursor.")
            .verify()
    }

    @Test
    fun `cursor should return an empty terminal page`() {
        eventStore.append(generateEventStream(namedAggregate.aggregateId(tenantId = generateGlobalId()))).block()

        eventStreamQueryBackend.cursor(
            CursorQuery(
                TenantIdFilter("missing"),
                sort = listOf(Sort(QueryField("id"), Sort.Direction.ASC)),
            ),
        ).test()
            .expectNext(CursorPage(emptyList(), null))
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

private fun EventStreamQueryBackend.single(query: ISingleQuery): Mono<ObjectNode> =
    Mono.defer { requiredQueryModelSchemaProvider().schema() }.flatMap { schema ->
        single(ResolvedQuery(schema.resolve(query).requireAccepted(QuerySchemaValidationMode.COMPATIBLE), schema))
    }

private fun EventStreamQueryBackend.list(query: IListQuery): Flux<ObjectNode> =
    Mono.defer { requiredQueryModelSchemaProvider().schema() }.flatMapMany { schema ->
        list(ResolvedQuery(schema.resolve(query).requireAccepted(QuerySchemaValidationMode.COMPATIBLE), schema))
    }

private fun EventStreamQueryBackend.paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> =
    Mono.defer { requiredQueryModelSchemaProvider().schema() }.flatMap { schema ->
        paged(ResolvedQuery(schema.resolve(query).requireAccepted(QuerySchemaValidationMode.COMPATIBLE), schema))
    }

private fun EventStreamQueryBackend.cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> =
    Mono.defer { requiredQueryModelSchemaProvider().schema() }.flatMap { schema ->
        cursor(ResolvedQuery(schema.resolve(query).requireAccepted(QuerySchemaValidationMode.COMPATIBLE), schema))
    }

private fun EventStreamQueryBackend.count(filter: FilterExpression): Mono<Long> =
    Mono.defer { requiredQueryModelSchemaProvider().schema() }.flatMap { schema ->
        count(ResolvedQuery(schema.resolve(filter).requireAccepted(QuerySchemaValidationMode.COMPATIBLE), schema))
    }

private fun EventStreamQueryBackend.aggregate(query: AggregationQuery): Flux<ObjectNode> =
    Mono.defer { requiredQueryModelSchemaProvider().schema() }.flatMapMany { schema ->
        aggregate(ResolvedQuery(schema.resolve(query).requireAccepted(QuerySchemaValidationMode.COMPATIBLE), schema))
    }

private fun ISingleQuery.query(backend: EventStreamQueryBackend): Mono<ObjectNode> = backend.single(this)
private fun ISingleQuery.dynamicQuery(backend: EventStreamQueryBackend): Mono<ObjectNode> = backend.single(this)
private fun IListQuery.query(backend: EventStreamQueryBackend): Flux<ObjectNode> = backend.list(this)
private fun IListQuery.dynamicQuery(backend: EventStreamQueryBackend): Flux<ObjectNode> = backend.list(this)
private fun IPagedQuery.query(backend: EventStreamQueryBackend) = backend.paged(this)
private fun IPagedQuery.dynamicQuery(backend: EventStreamQueryBackend) = backend.paged(this)
private fun FilterExpression.count(backend: EventStreamQueryBackend): Mono<Long> = backend.count(this)
private fun AggregationQuery.query(backend: EventStreamQueryBackend): Flux<ObjectNode> = backend.aggregate(this)
