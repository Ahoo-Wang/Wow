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

package me.ahoo.wow.query.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
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
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.filter.EventStreamQueryFilter
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryFilter
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.jvm.javaField

class DefaultEventStreamQueryGatewayTest {
    @Test
    fun `backend should return an empty terminal cursor page`() {
        NoOpEventStreamQueryBackend(MOCK_AGGREGATE_METADATA).cursor(CursorQuery(MatchAllFilter))
            .test()
            .assertNext { page ->
                page.list.assert().isEmpty()
                page.nextCursor.assert().isNull()
            }
            .verifyComplete()
    }

    @Test
    fun `typed result should materialize after the event filter chain`() {
        val eventStream = generateEventStream(MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId()))
        val calls = CopyOnWriteArrayList<String>()
        val gateway = DefaultEventStreamQueryGateway(
            MOCK_AGGREGATE_METADATA,
            backend { Mono.fromSupplier { eventStream.toJsonNode<ObjectNode>() } },
            listOf(generic(calls), event(calls), snapshot(calls)),
            ErrorHandler { _, error -> Mono.error(error) },
        )

        gateway.dynamicSingle(singleQuery { }).block()!!.path("id").textValue().assert().isEqualTo(eventStream.id)
        gateway.single(singleQuery { }).block()!!.id.assert().isEqualTo(eventStream.id)
        calls.assert().isEqualTo(listOf("generic", "event", "generic", "event"))
    }

    @Test
    fun `event gateway should mask dynamic and typed results with schema body type validation`() {
        val eventStream = generateEventStream(
            MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId()),
            eventCount = 1,
            createdEventSupplier = { MockAggregateCreated("secret") },
        )
        val raw = eventStream.toJsonNode<ObjectNode>()
        val bodyType = raw.path("body").path(0).path("bodyType").stringValue()
        val gateway = DefaultEventStreamQueryGateway(
            MOCK_AGGREGATE_METADATA,
            SchemaEventBackend(eventStream::toJsonNode, eventSchema(bodyType)),
            errorHandler = ErrorHandler { _, error -> Mono.error(error) },
        )

        gateway.dynamicSingle(singleQuery { }).block()!!
            .path("body").path(0).path("body").path("data").stringValue()
            .assert().isEqualTo("******")
        val typed = gateway.single(singleQuery { }).block()!!
        (typed.body.single().body as MockAggregateCreated).data.assert().isEqualTo("******")

        val query = CursorQuery(MatchAllFilter, sort = listOf(Sort("id", Sort.Direction.ASC)))
        val dynamicCursor = gateway.dynamicCursor(query).block()!!
        dynamicCursor.nextCursor.assert().isEqualTo("next")
        dynamicCursor.list.single().path("body").path(0).path("body").path("data").stringValue()
            .assert().isEqualTo("******")
        val typedCursor = gateway.cursor(query).block()!!
        typedCursor.nextCursor.assert().isEqualTo("next")
        (typedCursor.list.single().body.single().body as MockAggregateCreated).data.assert().isEqualTo("******")
    }

    @Test
    fun `event gateway should refresh masker when body type schema changes`() {
        val eventStream = generateEventStream(
            MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId()),
            eventCount = 1,
            createdEventSupplier = { MockAggregateCreated("secret") },
        )
        val currentNode = AtomicReference(eventStream.toJsonNode<ObjectNode>())
        val oldType = currentNode.get().path("body").path(0).path("bodyType").stringValue()
        val currentSchema = AtomicReference(eventSchema(oldType))
        val backend = SchemaEventBackend(
            nodeSupplier = { currentNode.get().deepCopy() },
            modelSchema = { currentSchema.get() },
        )
        val gateway = DefaultEventStreamQueryGateway(
            MOCK_AGGREGATE_METADATA,
            backend,
            errorHandler = ErrorHandler { _, error -> Mono.error(error) },
        )

        gateway.dynamicSingle(singleQuery { }).block()!!
        val newType = "$oldType-refreshed"
        currentNode.set(
            currentNode.get().deepCopy().also { node ->
                (node.path("body").path(0) as ObjectNode).put("bodyType", newType)
            }
        )
        currentSchema.set(eventSchema(newType))

        gateway.dynamicSingle(singleQuery { }).block()!!
            .path("body").path(0).path("body").path("data").stringValue()
            .assert().isEqualTo("******")
    }

    private fun generic(calls: MutableList<String>) = object : QueryFilter<QueryContext<*, *>> {
        override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
            calls += "generic"
            return next.filter(context)
        }
    }

    private fun event(calls: MutableList<String>) = object : EventStreamQueryFilter {
        override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
            calls += "event"
            return next.filter(context)
        }
    }

    private fun snapshot(calls: MutableList<String>) = object : SnapshotQueryFilter {
        override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
            calls += "snapshot"
            return next.filter(context)
        }
    }

    private fun backend(single: () -> Mono<ObjectNode>) = object : EventStreamQueryBackend {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override fun single(query: ISingleQuery): Mono<ObjectNode> = single()
        override fun list(query: IListQuery): Flux<ObjectNode> = Flux.empty()
        override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = Mono.just(PagedList.empty())
        override fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> =
            Mono.just(CursorPage(emptyList(), null))
        override fun count(filter: FilterExpression): Mono<Long> = Mono.just(0)
        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.empty()
    }

    private class SchemaEventBackend(
        private val nodeSupplier: () -> ObjectNode,
        private val modelSchema: () -> QueryModelSchema,
    ) : EventStreamQueryBackend, QueryModelSchemaProvider {
        constructor(nodeSupplier: () -> ObjectNode, modelSchema: QueryModelSchema) : this(nodeSupplier, { modelSchema })

        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override fun schema(): Mono<QueryModelSchema> = Mono.fromSupplier(modelSchema)
        override fun refresh(): Mono<QueryModelSchema> = schema()
        override fun single(query: ISingleQuery): Mono<ObjectNode> = Mono.fromSupplier(nodeSupplier)
        override fun list(query: IListQuery): Flux<ObjectNode> = Flux.empty()
        override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = Mono.just(PagedList.empty())
        override fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> =
            Mono.fromSupplier { CursorPage(listOf(nodeSupplier()), "next") }
        override fun count(filter: FilterExpression): Mono<Long> = Mono.just(0)
        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.empty()
    }

    private companion object {
        fun eventSchema(bodyType: String): QueryModelSchema {
            val annotation = Masked::data.javaField!!.getAnnotation(Mask::class.java)
            val rule = MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
            return QueryModelSchema(
                model = QueryModel.EVENT_STREAM,
                capabilities = emptySet(),
                fields = mapOf(
                    LogicalField("body.body.data") to fieldSchema(maskRule = rule),
                    LogicalField("body.bodyType") to fieldSchema(
                        enumValues = listOf(JsonSerializer.valueToTree(bodyType)),
                    ),
                ),
            )
        }

        fun fieldSchema(
            enumValues: List<tools.jackson.databind.JsonNode>? = null,
            maskRule: MaskRule? = null,
        ) = QueryFieldSchema(
            title = null,
            description = null,
            enumValues = enumValues,
            valueTypes = setOf(QueryValueType.STRING),
            nullable = false,
            required = true,
            cardinality = QueryCardinality.SINGLE,
            semanticType = null,
            dynamicChildren = false,
            bindings = emptyMap(),
            maskRule = maskRule,
        )
    }

    private data class Masked(@field:Mask val data: String)
}
