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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.mask.CompiledMask
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.KeepMask
import me.ahoo.wow.api.query.mask.KeepMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.filter.DefaultQueryContext
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.mask.SchemaMaskQueryFilter
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.resolve
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
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
    fun `schema mask filter should wrap the downstream result`() {
        val backend = SchemaSnapshotBackend(Mono.just(maskedSchema()))
        val context = DefaultQueryContext<ISingleQuery, Mono<ObjectNode>>(
            QueryType.SINGLE,
            MOCK_AGGREGATE_METADATA,
        ).setQuery(singleQuery { })
        val downstream = FilterChain<QueryContext<*, *>> { downstreamContext ->
            downstreamContext.asSingleQuery().setResult(Mono.fromSupplier(::snapshotNode))
            Mono.empty()
        }

        val result = SchemaMaskQueryFilter(backend).filter(context, downstream)
            .then(Mono.defer { context.getRequiredResult() })

        StepVerifier.create(result)
            .assertNext { it.stateValue().assert().isEqualTo("***********") }
            .verifyComplete()
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
        order.take(5).assert().isEqualTo(listOf("a-request", "b-request", "backend", "b-result", "a-result"))
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
        val query = CursorQuery(MatchAllFilter, sort = listOf(Sort("aggregateId", Sort.Direction.ASC)))

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
    fun `backend should reject cursor by default`() {
        NoOpSnapshotQueryBackend(MOCK_AGGREGATE_METADATA).cursor(CursorQuery(MatchAllFilter))
            .test().expectErrorMessage("Cursor query is not supported.").verify()
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
    fun `gateway should refresh masker when mask rule changes`() {
        val current = AtomicReference(maskedSchema())
        val backend = SchemaSnapshotBackend(schemaPublisher = { Mono.just(current.get()) })
        val gateway = gateway(backend)

        gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("***********")
        val annotation = Kept::value.javaField!!.getAnnotation(KeepMask::class.java)
        val rule = MaskRule(KeepMaskStrategy::class, annotation, KeepMaskStrategy.compile(annotation))
        current.set(
            QueryModelSchema(
                QueryModel.SNAPSHOT,
                emptySet(),
                mapOf(LogicalField("state.value") to fieldSchema(rule)),
            ),
        )
        gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("st*******ue")
    }

    @Test
    fun `mask execution errors should fail the publisher and be observed by error handler`() {
        val failure = IllegalStateException("mask failed")
        val observed = CopyOnWriteArrayList<Throwable>()
        val annotation = Masked::value.javaField!!.getAnnotation(Mask::class.java)
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(
                LogicalField("state.value") to fieldSchema(
                    MaskRule(FullMaskStrategy::class, annotation, CompiledMask { throw failure }),
                ),
            ),
        )

        StepVerifier.create(
            gateway(
                SchemaSnapshotBackend(Mono.just(schema)),
                errorHandler = ErrorHandler { _, error ->
                    observed += error
                    Mono.empty()
                },
            ).dynamicSingle(singleQuery { }),
        ).expectErrorMatches { it === failure }.verify()
        observed.assert().containsExactly(failure)
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
    fun `count should not load mask schema`() {
        val backend = SchemaSnapshotBackend(Mono.error(QuerySchemaUnavailableException("unused")))
        val gateway = gateway(backend)

        gateway.count(MatchAllFilter).block().assert().isOne()
        backend.schemaCalls.get().assert().isZero()
    }

    @Test
    fun `aggregation schema failure should stop execution and be observed by error handler`() {
        val failure = QuerySchemaUnavailableException("aggregation unavailable")
        val observed = CopyOnWriteArrayList<Throwable>()
        val backend = SchemaSnapshotBackend(Mono.error(failure))

        StepVerifier.create(
            gateway(
                backend,
                errorHandler = ErrorHandler { _, error ->
                    observed += error
                    Mono.empty()
                },
            ).aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))),
        )
            .expectErrorSatisfies { error -> error.assert().isSameAs(failure) }
            .verify()
        backend.resultSubscriptions.get().assert().isZero()
        observed.assert().containsExactly(failure)
    }

    @Test
    fun `schema mask filter should remain outermost after user filter sorting`() {
        val backend = SchemaSnapshotBackend(Mono.just(maskedSchema()))
        val gateway = gateway(backend, filters = listOf(RevealResultFilter()))

        gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("********")
        gateway.single(singleQuery { }).block()!!.state.value.assert().isEqualTo("********")
    }

    @Test
    fun `schema errors should fail the publisher and be observed by error handler`() {
        val failure = QuerySchemaUnavailableException("observed")
        val observed = CopyOnWriteArrayList<Throwable>()
        val backend = SchemaSnapshotBackend(Mono.error(failure))

        StepVerifier.create(
            gateway(
                backend,
                errorHandler = ErrorHandler { _, error ->
                    observed += error
                    Mono.empty()
                }
            ).dynamicSingle(singleQuery { }),
        ).expectErrorMatches { it === failure }.verify()
        observed.assert().containsExactly(failure)
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
                errorHandler = ErrorHandler { _, error ->
                    observed += error
                    Mono.empty()
                }
            ).dynamicSingle(singleQuery { }),
        ).expectErrorMatches { error ->
            error is QuerySchemaValidationException && observed.singleOrNull() === error
        }
            .verify()
        observed.single().assert().isInstanceOf(QuerySchemaValidationException::class.java)
    }

    private fun gateway(
        backend: SnapshotQueryBackend,
        filters: List<QueryFilter<QueryContext<*, *>>> = emptyList(),
        errorHandler: ErrorHandler<QueryContext<*, *>> = ErrorHandler { _, error -> Mono.error(error) },
    ): DefaultSnapshotQueryGateway<TestState> = DefaultSnapshotQueryGateway(
        namedAggregate = MOCK_AGGREGATE_METADATA,
        backend = backend,
        targetType = JsonSerializer.typeFactory.constructParametricType(
            MaterializedSnapshot::class.java,
            TestState::class.java,
        ),
        filters = filters,
        errorHandler = errorHandler,
    )

    private fun around(name: String, order: MutableList<String>) = object : QueryFilter<QueryContext<*, *>> {
        override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
            order += "$name-request"
            return next.filter(context).then(Mono.fromRunnable { order += "$name-result" })
        }
    }

    private class RecordingSnapshotBackend(
        override val namedAggregate: NamedAggregate,
        private val calls: MutableList<QueryType>,
        private val order: MutableList<String>? = null,
    ) : SnapshotQueryBackend {
        override val name: String = "recording"

        override fun single(query: ISingleQuery): Mono<ObjectNode> {
            calls += QueryType.SINGLE
            order?.add("backend")
            return Mono.fromSupplier(::snapshotNode)
        }

        override fun list(query: IListQuery): Flux<ObjectNode> = Flux.defer {
            Flux.just(record(QueryType.LIST, snapshotNode()))
        }

        override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = Mono.fromSupplier {
            PagedList(1, listOf(record(QueryType.PAGED, snapshotNode())))
        }

        override fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> = Mono.fromSupplier {
            CursorPage(listOf(record(QueryType.CURSOR, snapshotNode())), "next")
        }

        override fun count(filter: FilterExpression): Mono<Long> = Mono.fromSupplier {
            calls += QueryType.COUNT
            1L
        }

        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.defer {
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

    @Order(ORDER_FIRST, before = [SchemaMaskQueryFilter::class])
    private class RevealResultFilter : QueryFilter<QueryContext<*, *>> {
        override fun filter(
            context: QueryContext<*, *>,
            next: FilterChain<QueryContext<*, *>>,
        ): Mono<Void> = next.filter(context).then(
            Mono.fromRunnable {
                context.asSingleQuery().rewriteResult { result ->
                    result.map { node ->
                        (node.path("state") as ObjectNode).put("value", "revealed")
                        node
                    }
                }
            },
        )
    }

    private class SchemaSnapshotBackend(
        private val schemaPublisher: () -> Mono<QueryModelSchema>,
        private val nodeSupplier: () -> ObjectNode = ::snapshotNode,
    ) : SnapshotQueryBackend, QueryModelSchemaProvider {
        constructor(schemaPublisher: Mono<QueryModelSchema>) : this({ schemaPublisher })

        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override val name: String = "schema"
        val schemaCalls = AtomicInteger()
        val resultSubscriptions = AtomicInteger()

        override fun schema(): Mono<QueryModelSchema> = Mono.defer {
            schemaCalls.incrementAndGet()
            schemaPublisher()
        }

        override fun refresh(): Mono<QueryModelSchema> = schema()

        override fun single(query: ISingleQuery): Mono<ObjectNode> = Mono.fromSupplier {
            resultSubscriptions.incrementAndGet()
            nodeSupplier()
        }

        override fun list(query: IListQuery): Flux<ObjectNode> = Flux.defer {
            resultSubscriptions.incrementAndGet()
            Flux.just(nodeSupplier())
        }

        override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = Mono.fromSupplier {
            resultSubscriptions.incrementAndGet()
            PagedList(1, listOf(nodeSupplier()))
        }

        override fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> = Mono.fromSupplier {
            resultSubscriptions.incrementAndGet()
            CursorPage(listOf(nodeSupplier()), "next")
        }

        override fun count(filter: FilterExpression): Mono<Long> = Mono.just(1)

        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> =
            resolve(query, QuerySchemaValidationMode.COMPATIBLE).flatMapMany {
                Flux.defer {
                    resultSubscriptions.incrementAndGet()
                    Flux.just("""{"count":1}""".toJsonNode())
                }
            }
    }

    private companion object {
        fun ObjectNode.stateValue(): String = path("state").path("value").stringValue()

        fun maskedSchema(): QueryModelSchema {
            val annotation = Masked::value.javaField!!.getAnnotation(Mask::class.java)
            val rule = MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
            return QueryModelSchema(
                model = QueryModel.SNAPSHOT,
                capabilities = emptySet(),
                fields = mapOf(LogicalField("state.value") to fieldSchema(rule)),
            )
        }

        fun unmaskedSchema(): QueryModelSchema = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = emptyMap(),
        )

        fun fieldSchema(maskRule: MaskRule) = QueryFieldSchema(
            title = null,
            description = null,
            enumValues = null,
            valueTypes = setOf(QueryValueType.STRING),
            nullable = false,
            required = true,
            cardinality = QueryCardinality.SINGLE,
            semanticType = null,
            dynamicChildren = false,
            bindings = emptyMap(),
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
