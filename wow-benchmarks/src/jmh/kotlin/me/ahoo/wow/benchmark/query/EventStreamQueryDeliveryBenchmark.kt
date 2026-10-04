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

package me.ahoo.wow.benchmark.query

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.BackendPage
import me.ahoo.wow.query.CursorPositionCodec
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.PageWindow
import me.ahoo.wow.query.event.DefaultEventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Query delivery of event-stream rows through [DefaultEventStreamQueryGateway] over an in-process backend, so the
 * measured cost is the core's per-row work: the standard-JSON check (`BackendQueries.requireStandardJson`, which walks
 * every property of every event body) and the schema masker. Each row is a serialized domain event stream with
 * [eventsPerRow] events whose bodies carry [BODY_FIELDS] extra fields.
 *
 * The backend hands out a fresh deep copy of each row (the masker rewrites rows in place), so
 * [copyRowsOnly] measures that copy alone; delivery cost is `deliverList - copyRowsOnly`.
 *
 * Audit 9.3.0 C §F3 (design WP G2; Q2 compares against this).
 */
@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class EventStreamQueryDeliveryBenchmark {
    @Param("1", "10")
    var eventsPerRow: Int = 1

    @Param("off", "on")
    lateinit var mask: String

    private val namedAggregate: NamedAggregate = BenchmarkAggregates.namedAggregate
    private lateinit var templateRows: List<ObjectNode>
    private lateinit var gateway: EventStreamQueryGateway
    private lateinit var query: IListQuery

    @Setup
    fun setup() {
        require(eventsPerRow > 0) { "eventsPerRow must be greater than zero." }
        val masked = when (mask) {
            "off" -> false
            "on" -> true
            else -> error("Unsupported mask: $mask")
        }
        templateRows = List(ROWS) { index -> eventStreamRow(index) }
        val bodyType = templateRows.first().path("body").first().path("bodyType").asString()
        val schema = eventStreamSchema(masked, bodyType)
        val backend = object : EventStreamQueryBackend {
            override val namedAggregate: NamedAggregate = this@EventStreamQueryDeliveryBenchmark.namedAggregate
            override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON
            override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> =
                Flux.fromIterable(templateRows).map { it.deepCopy() }

            override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> =
                Mono.just(BackendPage(emptyList()))

            override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> = Mono.just(0L)
            override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
                Flux.empty()
        }
        val schemaProvider = object : QueryModelSchemaProvider {
            private val schemaMono = Mono.just(schema)
            override fun schema(): Mono<QueryModelSchema> = schemaMono
            override fun refresh(): Mono<QueryModelSchema> = schemaMono
        }
        gateway = DefaultEventStreamQueryGateway(
            namedAggregate = namedAggregate,
            backend = backend,
            schemaProvider = schemaProvider,
        )
        query = ListQuery(MatchAllFilter, limit = ROWS)

        val probe = checkNotNull(gateway.dynamicList(query).collectList().block())
        check(probe.size == ROWS) { "Expected $ROWS rows, got ${probe.size}." }
        val events = probe.first().path("body")
        check(events.size() == eventsPerRow) { "Expected $eventsPerRow events per row." }
        val secret = events.first().path("body").path("secret").asString()
        check((secret == SECRET) != masked) { "Mask [$mask] left secret as [$secret]." }
    }

    @Benchmark
    fun deliverList(blackhole: Blackhole) {
        blackhole.consume(gateway.dynamicList(query).collectList().block())
    }

    @Benchmark
    fun copyRowsOnly(blackhole: Blackhole) {
        for (row in templateRows) {
            blackhole.consume(row.deepCopy())
        }
    }

    private fun eventStreamRow(index: Int): ObjectNode {
        val aggregateId = BenchmarkAggregates.cartMetadata.aggregateId("query-delivery-$index")
        val events: List<Any> = List(eventsPerRow) { CartItemAdded(CartItem("product-$it", it + 1)) }
        val row = events.toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
            aggregateVersion = 0,
        ).toJsonNode<ObjectNode>()
        (row.path("body") as ArrayNode).forEach { event ->
            val body = event.path("body") as ObjectNode
            body.put("secret", SECRET)
            repeat(BODY_FIELDS) { field ->
                when (field % 4) {
                    0 -> body.put("text$field", "value-$index-$field")
                    1 -> body.put("count$field", index * field)
                    2 -> body.put("amount$field", index + field / 100.0)
                    else -> body.putObject("nested$field")
                        .put("code", "code-$field")
                        .put("enabled", field % 2 == 0)
                }
            }
        }
        return row
    }

    private fun eventStreamSchema(masked: Boolean, bodyType: String): QueryModelSchema {
        val string = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING))
        val values = buildMap {
            listOf("id", "aggregateId", "tenantId", "ownerId", "spaceId").forEach { put(QueryField(it), string) }
            put(QueryField("body.id"), string)
            put(QueryField("body.name"), string)
            // A masked event schema names the event types its body fields belong to.
            put(
                QueryField("body.bodyType"),
                QueryValueSchema(
                    QueryValueKind.SCALAR,
                    valueTypes = setOf(QueryValueType.STRING),
                    enumValues = listOf(JsonSerializer.valueToTree<JsonNode>(bodyType)),
                ),
            )
            put(
                QueryField("body.body.secret"),
                QueryValueSchema(
                    QueryValueKind.SCALAR,
                    valueTypes = setOf(QueryValueType.STRING),
                    maskRule = if (masked) MaskRule(SensitivityLevel.DISPLAY) else null,
                ),
            )
        }

        fun value(prefix: String): QueryValueSchema {
            val children = values.keys.map { it.path }.filter { it.startsWith(prefix) && it != prefix.removeSuffix(".") }
                .map { it.removePrefix(prefix).substringBefore('.') }.distinct()
            if (children.isEmpty() && prefix.isNotEmpty()) return values.getValue(QueryField(prefix.removeSuffix(".")))
            val objectValue = QueryValueSchema(QueryValueKind.OBJECT, properties = children.associateWith { value("$prefix$it.") })
            return if (prefix == "body.") QueryValueSchema(QueryValueKind.ARRAY, items = objectValue) else objectValue
        }
        val definition = LogicalQuerySchema(value(""))
        return QueryModelSchema(
            QueryModel.EVENT_STREAM,
            emptySet(),
            definition,
            definition.values.filterKeys { it.segments.isNotEmpty() }.mapValues { (path, value) ->
                val native = when (value.kind) {
                    QueryValueKind.SCALAR -> setOf(QueryCapability.PRESENCE, QueryCapability.EXACT_MATCH)
                    QueryValueKind.ARRAY -> setOf(QueryCapability.ELEMENT_SCOPE)
                    else -> emptySet()
                }
                QueryValueBindings(native.associateWith { QueryFieldBindingTemplate(path, null) }, path, path)
            },
        )
    }

    private companion object {
        const val ROWS = 100
        const val BODY_FIELDS = 16
        const val SECRET = "secret-value-0123456789"
    }
}
