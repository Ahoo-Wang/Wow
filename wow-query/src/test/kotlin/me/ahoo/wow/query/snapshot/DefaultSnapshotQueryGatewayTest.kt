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

package me.ahoo.wow.query.snapshot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.mask.CompiledMask
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.KeepMask
import me.ahoo.wow.api.query.mask.KeepMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.gatewaySchema
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import reactor.test.StepVerifier
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.jvm.javaField

class DefaultSnapshotQueryGatewayTest {
    @Test
    fun `snapshot masking should not remove event body type fields`() {
        val node = snapshotNode().also {
            it.putArray("body").addObject().put("bodyType", "keep")
        }
        val backend = SchemaSnapshotBackend({ Mono.just(maskedSchema()) }, nodeSupplier = { node.deepCopy() })

        val result = gateway(backend).dynamicSingle(
            singleQuery {
                projection { include("body.body.data") }
            },
        ).block()!!

        result.path("body").path(0).has("bodyType").assert().isTrue()
    }

    @Test
    fun `typed and dynamic single should share object-node chain`() {
        val backendCalls = CopyOnWriteArrayList<QueryType>()
        val order = CopyOnWriteArrayList<String>()
        val backend = RecordingSnapshotBackend(MOCK_AGGREGATE_METADATA, backendCalls, order)
        val gateway = gateway(backend, listOf(around("a", order), around("b", order)))

        gateway.dynamicSingle(singleQuery { }).block()!!.path("state").path("value").textValue()
            .assert().isEqualTo("state-value")
        gateway.single(singleQuery { }).block()!!.state.value.assert().isEqualTo("state-value")

        backendCalls.assert().isEqualTo(listOf(QueryType.SINGLE, QueryType.SINGLE))
        order.assert().isEqualTo(listOf("a-request", "b-request", "backend", "a-request", "b-request", "backend"))
    }

    @Test
    fun `gateway should forward every operation to its bound backend`() {
        val calls = CopyOnWriteArrayList<QueryType>()
        val backend = RecordingSnapshotBackend(MOCK_AGGREGATE_METADATA, calls)
        val gateway = gateway(backend)

        gateway.dynamicList(listQuery { }).collectList().block()!!.assert().hasSize(1)
        gateway.list(listQuery { }).collectList().block()!!.single().state.value.assert().isEqualTo("state-value")
        gateway.dynamicPaged(pagedQuery { }).block()!!.total.assert().isOne()
        gateway.paged(pagedQuery { }).block()!!.list.single().state.value.assert().isEqualTo("state-value")
        gateway.dynamicCursor(CursorQuery(MatchAllFilter)).block()!!.nextCursor.assert().isEqualTo("next")
        gateway.cursor(CursorQuery(MatchAllFilter)).block()!!.list.single().state.value
            .assert().isEqualTo("state-value")
        gateway.count(MatchAllFilter).block().assert().isOne()
        gateway.aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))))
            .single().block()!!.path("count").longValue().assert().isOne()

        calls.assert().isEqualTo(
            listOf(
                QueryType.LIST,
                QueryType.LIST,
                QueryType.PAGED,
                QueryType.PAGED,
                QueryType.CURSOR,
                QueryType.CURSOR,
                QueryType.COUNT,
                QueryType.AGGREGATION
            ),
        )
    }

    @Test
    fun `every backend operation receives the transformed query and bound schema`() {
        val receivedQueries = linkedMapOf<QueryType, Any>()
        val receivedSchemas = linkedMapOf<QueryType, QueryModelSchema>()
        val preparedSchemas = mutableListOf<QueryModelSchema>()
        val provider = SchemaSnapshotProvider { Mono.just(unmaskedSchema()) }
        val backend = RecordingSnapshotBackend(MOCK_AGGREGATE_METADATA, mutableListOf()) { type, query, schema ->
            receivedQueries[type] = query
            receivedSchemas[type] = schema
        }
        val filter = object : QueryFilter {
            override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> {
                preparedSchemas += context.schema
                return Mono.just(context.query.appendFilter(MatchNoneFilter))
            }
        }
        val gateway = gateway(backend, filters = listOf(filter), schemaProvider = provider)
        val sort = listOf(Sort(QueryField("aggregateId"), Sort.Direction.DESC))
        val single = SingleQuery(MatchAllFilter, sort = sort)
        val list = ListQuery(MatchAllFilter, sort = sort, limit = 7)
        val paged = PagedQuery(MatchAllFilter, sort = sort, pagination = Pagination(index = 2, size = 7))
        val cursor = CursorQuery(MatchAllFilter, sort = sort, size = 7, cursor = "opaque-cursor")
        val aggregation = AggregationQuery(metrics = listOf(AggregationMetric.Count("total")), limit = 7)
        gateway.dynamicSingle(single).block()
        gateway.dynamicList(list).collectList().block()
        gateway.dynamicPaged(paged).block()
        gateway.dynamicCursor(cursor).block()
        gateway.count(MatchAllFilter).block()
        gateway.aggregate(aggregation).collectList().block()

        receivedQueries.assert().isEqualTo(
            mapOf(
                QueryType.SINGLE to single.withFilter(MatchNoneFilter.appendFilter(DeletionFilter(DeletionState.ACTIVE))),
                QueryType.LIST to list.withFilter(MatchNoneFilter.appendFilter(DeletionFilter(DeletionState.ACTIVE))),
                QueryType.PAGED to paged.withFilter(MatchNoneFilter.appendFilter(DeletionFilter(DeletionState.ACTIVE))),
                QueryType.CURSOR to cursor.withFilter(MatchNoneFilter.appendFilter(DeletionFilter(DeletionState.ACTIVE))),
                QueryType.COUNT to MatchNoneFilter.appendFilter(DeletionFilter(DeletionState.ACTIVE)),
                QueryType.AGGREGATION to aggregation.withFilter(MatchNoneFilter.appendFilter(DeletionFilter(DeletionState.ACTIVE))),
            ),
        )
        receivedSchemas.assert().hasSize(6)
        preparedSchemas.assert().hasSize(6)
        provider.schemaCalls.get().assert().isEqualTo(6)
        receivedSchemas.values.zip(
            preparedSchemas
        ).forEach { (backend, prepared) -> backend.assert().isSameAs(prepared) }
    }

    @Test
    fun `gateway should mask typed and dynamic single list and paged results`() {
        val backend = SchemaSnapshotBackend(Mono.just(maskedSchema()))
        val gateway = gateway(backend)

        gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("***********")
        gateway.single(singleQuery { }).block()!!.state.value.assert().isEqualTo("***********")
        gateway.dynamicList(listQuery { }).single().block()!!.stateValue().assert().isEqualTo("***********")
        gateway.list(listQuery { }).single().block()!!.state.value.assert().isEqualTo("***********")
        gateway.dynamicPaged(pagedQuery { }).block()!!.list.single().stateValue().assert().isEqualTo("***********")
        gateway.paged(pagedQuery { }).block()!!.list.single().state.value.assert().isEqualTo("***********")
        backend.schemaCalls.get().assert().isEqualTo(6)
    }

    @Test
    fun `cursor should mask raw page and preserve next cursor`() {
        val backend = SchemaSnapshotBackend(Mono.just(maskedSchema()))
        val gateway = gateway(backend)
        val query = CursorQuery(MatchAllFilter, sort = listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC)))

        gateway.dynamicCursor(query)
            .test()
            .assertNext { page ->
                page.nextCursor.assert().isEqualTo("next")
                page.list.single().path("state").path("value").textValue().assert().isNotEqualTo("state-value")
            }.verifyComplete()
        gateway.cursor(query).test().assertNext { page ->
            page.nextCursor.assert().isEqualTo("next")
            page.list.single().state.value.assert().isNotEqualTo("state-value")
        }.verifyComplete()
    }

    @Test
    fun `backend should return an empty terminal cursor page`() {
        val schema = unmaskedSchema()
        val query = CursorQuery(
            MatchAllFilter,
            sort = listOf(
                me.ahoo.wow.api.query.Sort(QueryField("aggregateId"), me.ahoo.wow.api.query.Sort.Direction.ASC)
            )
        )
        NoOpSnapshotQueryBackend(MOCK_AGGREGATE_METADATA).cursor(query, schema)
            .test()
            .assertNext { page ->
                page.list.assert().isEmpty()
                page.nextCursor.assert().isNull()
            }
            .verifyComplete()
    }

    @Test
    fun `gateway should load an unmasked schema for each result query and leave raw results unchanged`() {
        val backend = SchemaSnapshotBackend(Mono.just(unmaskedSchema()))
        val gateway = gateway(backend)

        gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("state-value")
        gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("state-value")
        backend.schemaCalls.get().assert().isEqualTo(2)
    }

    @Test
    fun `gateway should refresh masker when schema becomes masked`() {
        val current = AtomicReference(unmaskedSchema())
        val backend = SchemaSnapshotBackend(schemaPublisher = { Mono.just(current.get()) })
        val gateway = gateway(backend)

        gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("state-value")
        current.set(maskedSchema())
        gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("***********")
        backend.schemaCalls.get().assert().isEqualTo(2)
    }

    @Test
    fun `gateway should pin one schema generation across resolve and mask`() {
        val backend = SwitchingSchemaSnapshotBackend()

        gateway(backend).dynamicSingle(singleQuery { }).block()!!
            .stateValue().assert().isEqualTo("***********")
        backend.schemaCalls.get().assert().isOne()
    }

    @Test
    fun `gateway should refresh masker when mask rule changes`() {
        val current = AtomicReference(maskedSchema())
        val backend = SchemaSnapshotBackend(schemaPublisher = { Mono.just(current.get()) })
        val gateway = gateway(backend)

        gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("***********")
        val annotation = Kept::value.javaField!!.getAnnotation(KeepMask::class.java)
        val rule = MaskRule(KeepMaskStrategy::class, annotation, KeepMaskStrategy.compile(annotation))
        current.set(
            gatewaySchema(
                QueryModel.SNAPSHOT,
                emptySet(),
                mapOf(QueryField("state.value") to fieldSchema(rule)),
            ),
        )
        gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("st*******ue")
    }

    @Test
    fun `mask execution errors should fail the publisher and be observed by error handler`() {
        val failure = IllegalStateException("mask failed")
        val observed = CopyOnWriteArrayList<Throwable>()
        val annotation = Masked::value.javaField!!.getAnnotation(Mask::class.java)
        val schema = gatewaySchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(
                QueryField("state.value") to fieldSchema(
                    MaskRule(FullMaskStrategy::class, annotation, CompiledMask { throw failure }),
                ),
            ),
        )

        StepVerifier.create(
            gateway(
                SchemaSnapshotBackend(Mono.just(schema)),
                observer = errorObserver { observed += it },
            ).dynamicSingle(singleQuery { }),
        ).expectErrorSatisfies { error ->
            error.assert().isInstanceOf(QuerySchemaValidationException::class.java)
            error.message.assert().isEqualTo("Mask strategy execution failed.")
            error.cause.assert().isSameAs(failure)
            observed.single().assert().isSameAs(error)
        }.verify()
    }

    @Test
    fun `default error handler should not log mask strategy cause`() {
        val failure = IllegalStateException("secret-value")
        val annotation = Masked::value.javaField!!.getAnnotation(Mask::class.java)
        val schema = gatewaySchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(
                QueryField("state.value") to fieldSchema(
                    MaskRule(FullMaskStrategy::class, annotation, CompiledMask { throw failure }),
                ),
            ),
        )
        val backend = SchemaSnapshotBackend(Mono.just(schema))
        val errors = captureErrors {
            DefaultSnapshotQueryGateway<TestState>(
                namedAggregate = MOCK_AGGREGATE_METADATA,
                binding = QueryBackendBinding(backend, backend.schemaProvider),

                targetType = JsonSerializer.typeFactory.constructParametricType(
                    MaterializedSnapshot::class.java,
                    TestState::class.java,
                ),
            ).dynamicSingle(singleQuery { }).test()
                .expectError(QuerySchemaValidationException::class.java)
                .verify()
        }

        errors.assert().hasSize(1)
        errors.single().formattedMessage.assert()
            .isEqualTo("Mask strategy execution failed.")
            .doesNotContain("secret-value")
        errors.single().throwableProxy.assert().isNull()
    }

    @Test
    fun `gateway should retry schema loading after an earlier request fails`() {
        val failure = QuerySchemaUnavailableException("first")
        val attempts = AtomicInteger()
        val backend = SchemaSnapshotBackend(schemaPublisher = {
            if (attempts.getAndIncrement() == 0) Mono.error(failure) else Mono.just(maskedSchema())
        })
        val gateway = gateway(backend)

        StepVerifier.create(gateway.dynamicSingle(singleQuery { }))
            .expectErrorMatches { it === failure }
            .verify()
        gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("***********")
        backend.schemaCalls.get().assert().isEqualTo(2)
    }

    @Test
    fun `retry should resubscribe schema loading after an error`() {
        val attempts = AtomicInteger()
        val backend = SchemaSnapshotBackend(schemaPublisher = {
            if (attempts.getAndIncrement() == 0) {
                Mono.error(QuerySchemaUnavailableException("retry"))
            } else {
                Mono.just(maskedSchema())
            }
        })

        gateway(backend).dynamicSingle(singleQuery { }).retry(1).block()!!
            .stateValue().assert().isEqualTo("***********")
        backend.schemaCalls.get().assert().isEqualTo(2)
    }

    @Test
    fun `gateway should fail result queries before subscribing backend when schema fails`() {
        val failure = QuerySchemaUnavailableException("unavailable")
        val backend = SchemaSnapshotBackend(Mono.error(failure))

        StepVerifier.create(gateway(backend).dynamicSingle(singleQuery { }))
            .expectErrorMatches { it === failure }
            .verify()
        backend.resultSubscriptions.get().assert().isZero()
    }

    @Test
    fun `count should load one schema for resolution without a second mask lookup`() {
        val backend = SchemaSnapshotBackend(Mono.just(unmaskedSchema()))
        val gateway = gateway(backend)

        gateway.count(MatchAllFilter).block().assert().isOne()
        backend.schemaCalls.get().assert().isOne()
    }

    @Test
    fun `aggregation schema failure should be observed outside schema loading`() {
        val failure = QuerySchemaUnavailableException("aggregation unavailable")
        val observed = CopyOnWriteArrayList<Throwable>()
        val backend = SchemaSnapshotBackend(Mono.error(failure))

        StepVerifier.create(
            gateway(
                backend,
                observer = errorObserver { observed += it },
            ).aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))),
        )
            .expectErrorSatisfies { error -> error.assert().isSameAs(failure) }
            .verify()
        backend.resultSubscriptions.get().assert().isZero()
        observed.single().assert().isSameAs(failure)
    }

    @Test
    fun `schema errors should be observed without query context`() {
        val failure = QuerySchemaUnavailableException("observed")
        val observed = CopyOnWriteArrayList<Throwable>()
        val backend = SchemaSnapshotBackend(Mono.error(failure))

        StepVerifier.create(
            gateway(
                backend,
                observer = errorObserver { observed += it }
            ).dynamicSingle(singleQuery { }),
        ).expectErrorMatches { it === failure }.verify()
        observed.single().assert().isSameAs(failure)
    }

    @Test
    fun `mask wire errors should fail the publisher and be observed by error handler`() {
        val observed = CopyOnWriteArrayList<Throwable>()
        val backend = SchemaSnapshotBackend(
            schemaPublisher = { Mono.just(maskedSchema()) },
            nodeSupplier = {
                snapshotNode().also { node -> (node.path("state") as ObjectNode).put("value", 42) }
            },
        )

        StepVerifier.create(
            gateway(
                backend,
                observer = errorObserver { observed += it }
            ).dynamicSingle(singleQuery { }),
        ).expectErrorMatches { error ->
            error is QuerySchemaValidationException && observed.singleOrNull() === error
        }
            .verify()
        observed.single().assert().isInstanceOf(QuerySchemaValidationException::class.java)
    }

    private fun gateway(
        backend: SnapshotQueryBackend,
        filters: List<QueryFilter> = emptyList(),
        observer: QueryObserver = object : QueryObserver {},
        schemaProvider: QueryModelSchemaProvider = defaultSchemaProvider,
    ): DefaultSnapshotQueryGateway<TestState> = DefaultSnapshotQueryGateway(
        namedAggregate = MOCK_AGGREGATE_METADATA,
        binding = QueryBackendBinding(backend, schemaProvider),

        targetType = JsonSerializer.typeFactory.constructParametricType(
            MaterializedSnapshot::class.java,
            TestState::class.java,
        ),
        filters = filters,
        observer = observer,
    )

    private fun gateway(
        backend: SchemaSnapshotBackend,
        filters: List<QueryFilter> = emptyList(),
        observer: QueryObserver = object : QueryObserver {},
    ): DefaultSnapshotQueryGateway<TestState> = DefaultSnapshotQueryGateway(
        namedAggregate = MOCK_AGGREGATE_METADATA,
        binding = QueryBackendBinding(backend, backend.schemaProvider),

        targetType = JsonSerializer.typeFactory.constructParametricType(
            MaterializedSnapshot::class.java,
            TestState::class.java,
        ),
        filters = filters,
        observer = observer,
    )

    private fun gateway(backend: SwitchingSchemaSnapshotBackend): DefaultSnapshotQueryGateway<TestState> =
        DefaultSnapshotQueryGateway(
            namedAggregate = MOCK_AGGREGATE_METADATA,
            binding = QueryBackendBinding(backend, backend.schemaProvider),

            targetType = JsonSerializer.typeFactory.constructParametricType(
                MaterializedSnapshot::class.java,
                TestState::class.java,
            ),
            observer = object : QueryObserver {},
        )

    private fun errorObserver(callback: (Throwable) -> Unit) = object : QueryObserver {
        override fun onError(namedAggregate: NamedAggregate, queryType: QueryType, error: Throwable) = callback(error)
    }

    private fun captureErrors(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.filter { it.level == Level.ERROR }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun around(name: String, order: MutableList<String>) = object : QueryFilter {
        override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> {
            order += "$name-request"
            return Mono.just(context.query)
        }
    }

    private class RecordingSnapshotBackend(
        override val namedAggregate: NamedAggregate,
        private val calls: MutableList<QueryType>,
        private val order: MutableList<String>? = null,
        private val observe: (QueryType, Any, QueryModelSchema) -> Unit = { _, _, _ -> },
    ) : SnapshotQueryBackend {
        override val name: String = "recording"

        override fun single(query: ISingleQuery, schema: QueryModelSchema): Mono<ObjectNode> {
            observe(QueryType.SINGLE, query, schema)
            calls += QueryType.SINGLE
            order?.add("backend")
            return Mono.fromSupplier(::snapshotNode)
        }

        override fun list(query: IListQuery, schema: QueryModelSchema): Flux<ObjectNode> = Flux.defer {
            observe(QueryType.LIST, query, schema)
            Flux.just(record(QueryType.LIST, snapshotNode()))
        }

        override fun paged(query: IPagedQuery, schema: QueryModelSchema): Mono<PagedList<ObjectNode>> = Mono.fromSupplier {
            observe(QueryType.PAGED, query, schema)
            PagedList(1, listOf(record(QueryType.PAGED, snapshotNode())))
        }

        override fun cursor(query: ICursorQuery, schema: QueryModelSchema): Mono<CursorPage<ObjectNode>> = Mono.fromSupplier {
            observe(QueryType.CURSOR, query, schema)
            CursorPage(listOf(record(QueryType.CURSOR, snapshotNode())), "next")
        }

        override fun count(query: FilterExpression, schema: QueryModelSchema): Mono<Long> = Mono.fromSupplier {
            observe(QueryType.COUNT, query, schema)
            calls += QueryType.COUNT
            1L
        }

        override fun aggregate(query: AggregationQuery, schema: QueryModelSchema): Flux<ObjectNode> = Flux.defer {
            observe(QueryType.AGGREGATION, query, schema)
            calls += QueryType.AGGREGATION
            Flux.just("""{"count":1}""".toJsonNode())
        }

        private fun record(queryType: QueryType, node: ObjectNode): ObjectNode {
            calls += queryType
            order?.add("backend")
            return node
        }
    }

    private data class TestState(val value: String)

    private class SchemaSnapshotBackend(
        private val schemaPublisher: () -> Mono<QueryModelSchema>,
        private val nodeSupplier: () -> ObjectNode = ::snapshotNode,
    ) : SnapshotQueryBackend {
        constructor(schemaPublisher: Mono<QueryModelSchema>) : this({ schemaPublisher })

        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override val name: String = "schema"
        val schemaProvider = SchemaSnapshotProvider(schemaPublisher)
        val schemaCalls: AtomicInteger
            get() = schemaProvider.schemaCalls
        val resultSubscriptions = AtomicInteger()

        override fun single(query: ISingleQuery, schema: QueryModelSchema): Mono<ObjectNode> = Mono.fromSupplier {
            resultSubscriptions.incrementAndGet()
            nodeSupplier()
        }

        override fun list(query: IListQuery, schema: QueryModelSchema): Flux<ObjectNode> = Flux.defer {
            resultSubscriptions.incrementAndGet()
            Flux.just(nodeSupplier())
        }

        override fun paged(query: IPagedQuery, schema: QueryModelSchema): Mono<PagedList<ObjectNode>> = Mono.fromSupplier {
            resultSubscriptions.incrementAndGet()
            PagedList(1, listOf(nodeSupplier()))
        }

        override fun cursor(query: ICursorQuery, schema: QueryModelSchema): Mono<CursorPage<ObjectNode>> = Mono.fromSupplier {
            resultSubscriptions.incrementAndGet()
            CursorPage(listOf(nodeSupplier()), "next")
        }

        override fun count(query: FilterExpression, schema: QueryModelSchema): Mono<Long> = Mono.just(1)

        override fun aggregate(query: AggregationQuery, schema: QueryModelSchema): Flux<ObjectNode> = Flux.defer {
            resultSubscriptions.incrementAndGet()
            Flux.just("""{"count":1}""".toJsonNode())
        }
    }

    private class SchemaSnapshotProvider(
        private val schemaPublisher: () -> Mono<QueryModelSchema>,
    ) : QueryModelSchemaProvider {
        val schemaCalls = AtomicInteger()

        override fun schema(): Mono<QueryModelSchema> = Mono.defer {
            schemaCalls.incrementAndGet()
            schemaPublisher()
        }

        override fun refresh(): Mono<QueryModelSchema> = schema()
    }

    private class SwitchingSchemaSnapshotBackend : SnapshotQueryBackend by NoOpSnapshotQueryBackend(
        MOCK_AGGREGATE_METADATA
    ) {
        val schemaProvider = SwitchingSchemaProvider()
        val schemaCalls: AtomicInteger
            get() = schemaProvider.schemaCalls

        override fun single(query: ISingleQuery, schema: QueryModelSchema): Mono<ObjectNode> =
            Mono.fromSupplier(::snapshotNode)
    }

    private class SwitchingSchemaProvider : QueryModelSchemaProvider {
        private val current = AtomicReference(maskedSchema())
        val schemaCalls = AtomicInteger()

        override fun schema(): Mono<QueryModelSchema> {
            val selected = current.get()
            if (schemaCalls.incrementAndGet() == 1) {
                current.set(unmaskedSchema())
            }
            return Mono.just(selected)
        }

        override fun refresh(): Mono<QueryModelSchema> = schema()
    }

    private companion object {
        val defaultSchemaProvider = object : QueryModelSchemaProvider {
            private val schema = unmaskedSchema()
            override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)
            override fun refresh(): Mono<QueryModelSchema> = schema()
        }

        fun ObjectNode.stateValue(): String = path("state").path("value").stringValue()

        fun maskedSchema(): QueryModelSchema {
            val annotation = Masked::value.javaField!!.getAnnotation(Mask::class.java)
            val rule = MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
            return gatewaySchema(
                model = QueryModel.SNAPSHOT,
                capabilities = emptySet(),
                fields = mapOf(
                    QueryField("body.body.data") to stringValueSchema(),
                    QueryField("state.value") to fieldSchema(rule),
                    QueryField("aggregateId") to stringValueSchema(),
                ),
            )
        }

        fun unmaskedSchema(): QueryModelSchema = gatewaySchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = mapOf(QueryField("aggregateId") to stringValueSchema()),
        )

        fun stringValueSchema() = me.ahoo.wow.query.schema.QueryValueSchema(
            me.ahoo.wow.api.query.schema.QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.STRING),
        )

        fun fieldSchema(maskRule: MaskRule) = me.ahoo.wow.query.schema.QueryValueSchema(
            me.ahoo.wow.api.query.schema.QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.STRING),
            maskRule = maskRule,
        )

        fun snapshotNode(): ObjectNode = """
            {
              "contextName":"mock",
              "aggregateName":"mock",
              "tenantId":"tenant",
              "ownerId":"_default_",
              "spaceId":"_default_",
              "aggregateId":"aggregate",
              "version":1,
              "eventId":"event",
              "firstOperator":"operator",
              "operator":"operator",
              "firstEventTime":1,
              "eventTime":1,
              "state":{"value":"state-value"},
              "snapshotTime":1,
              "tags":{},
              "deleted":false
            }
        """.toJsonNode()
    }

    private data class Masked(@field:Mask val value: String)
    private data class Kept(@field:KeepMask(prefix = 2, suffix = 2) val value: String)
}
