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

package me.ahoo.wow.spring.boot.starter.webflux

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.query.QueryAutoConfiguration
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.query.DefaultQueryRequestScope
import me.ahoo.wow.webflux.route.query.HttpQueryGuard
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunctions
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.jvm.javaField

/**
 * Golden contract for the REST-visible outcome of invalid query requests: HTTP status, error code and error text.
 *
 * The query refactor must keep every entry unchanged (the REST API, including error texts, is a compatibility
 * contract). A case whose outcome changes on purpose is updated by rerunning with `WOW_GOLDEN_UPDATE=true` and
 * reviewing the diff of [GOLDEN].
 */
class QueryRestErrorContractTest {
    @Test
    fun `invalid query requests keep their REST status, error code and error text`() {
        val actual = JsonNodeFactory.instance.arrayNode()
        withClients { clients ->
            CASES.forEach { case ->
                val response = clients.getValue(case.guard).post().uri(case.route.path)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .bodyValue(case.body).exchange()
                    .expectBody(String::class.java).returnResult()
                actual.add(outcome(case, response.status.value(), response.responseBody))
            }
        }
        val expected = golden(actual)
        actual.size().assert().isEqualTo(expected.size())
        expected.zip(actual).forEach { (expectedCase, actualCase) ->
            actualCase.assert().describedAs(expectedCase.path("case").stringValue()).isEqualTo(expectedCase)
        }
    }

    private fun outcome(case: Case, status: Int, body: String?): ObjectNode {
        val result = JsonNodeFactory.instance.objectNode()
            .put("case", case.name)
            .put("route", case.route.handlerKey)
            .put("status", status)
        val error = body?.takeIf { it.isNotBlank() }?.let { runCatching { JsonSerializer.readTree(it) }.getOrNull() }
        if (error is ObjectNode && error.has("errorCode")) {
            result.set("errorCode", error.path("errorCode"))
            result.set("errorMsg", error.path("errorMsg"))
            error.get("bindingErrors")?.takeIf { it.isArray && !it.isEmpty }?.let { result.set("bindingErrors", it) }
        }
        return result
    }

    private fun golden(actual: JsonNode): JsonNode {
        if (System.getenv(UPDATE_ENV) == "true" || Files.notExists(GOLDEN)) {
            Files.createDirectories(GOLDEN.parent)
            Files.writeString(GOLDEN, JsonSerializer.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n")
        }
        return JsonSerializer.readTree(Files.readString(GOLDEN))
    }

    private fun withClients(verify: (Map<Guard, WebTestClient>) -> Unit) {
        val metadata = Order::class.java.aggregateRouteMetadata()
        val namedAggregate = metadata.aggregateMetadata.namedAggregate
        val backend = EmptyBackend(namedAggregate)
        val snapshotFactory = object : SnapshotQueryBackendFactory {
            override fun create(namedAggregate: NamedAggregate): QueryBackendBinding<SnapshotQueryBackend> =
                QueryBackendBinding(backend, SNAPSHOT_SCHEMA.asProvider())
        }
        val eventFactory = EventStreamQueryBackendFactory {
            QueryBackendBinding(backend, EVENT_STREAM_SCHEMA.asProvider())
        }
        ApplicationContextRunner().enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .withBean(SnapshotQueryBackendFactory::class.java, { snapshotFactory })
            .withBean(EventStreamQueryBackendFactory::class.java, { eventFactory })
            .run { context ->
                context.assert().hasNotFailed()
                val clients = Guard.entries.associateWith { guard ->
                    val module = WebFluxAutoConfiguration().queryRouteModule(
                        context,
                        snapshotFactory,
                        eventFactory,
                        DefaultQueryRequestScope,
                        WebFluxRequestExceptionHandler(),
                        guard.guard,
                    )
                    val router = RouterFunctions.route()
                    Route.entries.forEach { route ->
                        val contract = HttpRouteContract(
                            routeId = route.handlerKey,
                            method = Https.Method.POST,
                            path = route.path,
                            handlerKey = route.handlerKey,
                            handlerMetadata = HttpRouteHandlerMetadata.Aggregate(metadata),
                        )
                        router.POST(
                            route.path,
                            module.httpFactories.single { it.handlerKey == route.handlerKey }.create(contract),
                        )
                    }
                    WebTestClient.bindToRouterFunction(router.build()).build()
                }
                verify(clients)
            }
    }

    private class EmptyBackend(namedAggregate: NamedAggregate) :
        SnapshotQueryBackend by NoOpSnapshotQueryBackend(namedAggregate), EventStreamQueryBackend

    private enum class Guard(val guard: HttpQueryGuard) {
        DEFAULT(HttpQueryGuard()),
        STRICT(HttpQueryGuard(allowExpensiveOperators = false)),
    }

    private enum class Route(val handlerKey: String) {
        SNAPSHOT_SINGLE(BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE),
        SNAPSHOT_LIST(BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY),
        SNAPSHOT_PAGED(BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY),
        SNAPSHOT_CURSOR(BuiltInHttpRouteHandlerKeys.Snapshot.CURSOR_QUERY),
        SNAPSHOT_COUNT(BuiltInHttpRouteHandlerKeys.Snapshot.COUNT),
        SNAPSHOT_AGGREGATION(BuiltInHttpRouteHandlerKeys.Snapshot.AGGREGATION),
        EVENT_LIST(BuiltInHttpRouteHandlerKeys.Event.LIST_QUERY),
        EVENT_CURSOR(BuiltInHttpRouteHandlerKeys.Event.CURSOR_QUERY),
        EVENT_COUNT(BuiltInHttpRouteHandlerKeys.Event.COUNT),
        ;

        val path = "/$handlerKey"
    }

    private data class Case(val name: String, val route: Route, val body: String, val guard: Guard = Guard.DEFAULT)

    private class Masked(@field:Mask val value: String)

    private companion object {
        const val UPDATE_ENV = "WOW_GOLDEN_UPDATE"
        val GOLDEN: Path = Path.of("src/test/resources/golden/query-rest-error-contract.json")

        const val ALL = """{"op":"MATCH_ALL"}"""
        const val COUNT_METRIC = """{"type":"COUNT","alias":"count"}"""

        fun list(filter: String, extra: String = "") = """{"filter":$filter,"limit":10$extra}"""
        fun aggregation(extra: String) = """{"filter":$ALL$extra}"""

        val CASES = listOf(
            // Decoding
            Case("decode.invalid-json", Route.SNAPSHOT_LIST, "{"),
            Case(
                "decode.filter-and-condition",
                Route.SNAPSHOT_LIST,
                """{"filter":$ALL,"condition":{"operator":"ALL"},"limit":10}"""
            ),
            Case("decode.missing-filter", Route.SNAPSHOT_LIST, """{"limit":10}"""),
            Case("decode.null-filter", Route.SNAPSHOT_LIST, """{"filter":null,"limit":10}"""),
            Case("decode.unknown-operator", Route.SNAPSHOT_LIST, list("""{"op":"NOPE"}""")),
            Case("decode.unknown-property", Route.SNAPSHOT_LIST, list(ALL, ""","extra":1""")),
            Case(
                "decode.equality-array-value",
                Route.SNAPSHOT_LIST,
                list("""{"op":"EQ","field":"state.name","value":[1]}""")
            ),
            Case(
                "decode.nested-filter-without-op",
                Route.SNAPSHOT_LIST,
                list("""{"op":"AND","operands":[{"field":"state.name"}]}""")
            ),
            Case("decode.count-root-without-op", Route.SNAPSHOT_COUNT, """{"field":"state.name"}"""),
            Case(
                "decode.legacy-condition-accepted",
                Route.SNAPSHOT_LIST,
                """{"condition":{"operator":"ALL"},"limit":10,"ignored":1}"""
            ),
            // HTTP budgets
            Case("guard.list-limit-too-large", Route.SNAPSHOT_LIST, """{"filter":$ALL,"limit":1001}"""),
            Case("guard.list-limit-negative", Route.SNAPSHOT_LIST, """{"filter":$ALL,"limit":-1}"""),
            Case(
                "guard.page-index-zero",
                Route.SNAPSHOT_PAGED,
                """{"filter":$ALL,"pagination":{"index":0,"size":10}}"""
            ),
            Case(
                "guard.page-size-too-large",
                Route.SNAPSHOT_PAGED,
                """{"filter":$ALL,"pagination":{"index":1,"size":101}}"""
            ),
            Case(
                "guard.page-window-too-large",
                Route.SNAPSHOT_PAGED,
                """{"filter":$ALL,"pagination":{"index":101,"size":100}}"""
            ),
            Case("guard.cursor-size-too-large", Route.SNAPSHOT_CURSOR, """{"filter":$ALL,"size":101}"""),
            Case(
                "guard.filter-nodes-too-many",
                Route.SNAPSHOT_LIST,
                list(
                    """{"op":"OR","operands":[${
                        (1..130).joinToString(",") { """{"op":"EQ","field":"state.name","value":"n$it"}""" }
                    }]}""",
                ),
            ),
            Case(
                "guard.filter-values-too-many",
                Route.SNAPSHOT_LIST,
                list("""{"op":"IN","field":"state.name","values":[${(1..1001).joinToString(",") { "\"v$it\"" }}]}"""),
            ),
            Case(
                "guard.aggregation-limit-too-large",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(""","metrics":[$COUNT_METRIC],"limit":1001""")
            ),
            // HTTP gating with expensive operators disabled
            Case("strict.count-matches-all", Route.SNAPSHOT_COUNT, ALL, Guard.STRICT),
            Case(
                "strict.contains",
                Route.SNAPSHOT_LIST,
                list("""{"op":"CONTAINS","field":"state.name","value":"a"}"""),
                Guard.STRICT
            ),
            Case(
                "strict.aggregation-elements",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(""","elements":[{"path":"state.items"}],"metrics":[$COUNT_METRIC]"""),
                Guard.STRICT,
            ),
            Case(
                "strict.metric-sort",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","groupBy":[{"type":"TERMS","field":"state.name","alias":"name"}],"metrics":[$COUNT_METRIC],"sort":[{"field":"count","direction":"DESC"}]""",
                ),
                Guard.STRICT,
            ),
            Case(
                "strict.arithmetic-expression",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","metrics":[{"type":"NUMERIC","function":"SUM","alias":"total","expression":{"type":"BINARY","operator":"ADD","left":{"type":"FIELD","field":"state.amount"},"right":{"type":"CONSTANT","value":1}}}]""",
                ),
                Guard.STRICT,
            ),
            // Admission against the query model schema
            Case(
                "admission.unknown-field",
                Route.SNAPSHOT_LIST,
                list("""{"op":"EQ","field":"state.missing","value":"x"}""")
            ),
            Case(
                "admission.unsupported-capability",
                Route.SNAPSHOT_LIST,
                list("""{"op":"EQ","field":"state.note","value":"x"}""")
            ),
            Case(
                "admission.range-on-string",
                Route.SNAPSHOT_LIST,
                list("""{"op":"GT","field":"state.name","value":"a"}""")
            ),
            Case(
                "admission.value-type-mismatch",
                Route.SNAPSHOT_LIST,
                list("""{"op":"EQ","field":"state.amount","value":"abc"}""")
            ),
            Case(
                "admission.sort-unsupported",
                Route.SNAPSHOT_LIST,
                list(ALL, ""","sort":[{"field":"state.note","direction":"ASC"}]""")
            ),
            Case(
                "admission.cursor-sort-unsupported",
                Route.SNAPSHOT_CURSOR,
                """{"filter":$ALL,"size":10,"sort":[{"field":"state.name","direction":"ASC"}]}"""
            ),
            Case(
                "admission.element-scope-required",
                Route.SNAPSHOT_LIST,
                list("""{"op":"EQ","field":"state.items.sku","value":"x"}""")
            ),
            Case(
                "admission.element-match-unsupported",
                Route.SNAPSHOT_LIST,
                list("""{"op":"ELEMENT_MATCH","field":"state.tags","predicate":{"op":"EQ","field":"x","value":"y"}}"""),
            ),
            Case(
                "admission.is-empty-not-collection",
                Route.SNAPSHOT_LIST,
                list("""{"op":"IS_EMPTY","field":"state.name"}""")
            ),
            Case(
                "admission.relative-time-not-temporal",
                Route.SNAPSHOT_LIST,
                list("""{"op":"TODAY","field":"state.amount"}""")
            ),
            Case("admission.model-search-unsupported", Route.SNAPSHOT_LIST, list("""{"op":"SEARCH","query":"x"}""")),
            Case(
                "admission.projection-unknown-field",
                Route.SNAPSHOT_LIST,
                list(ALL, ""","projection":{"include":["state.missing"]}""")
            ),
            Case(
                "admission.aggregate-protected-field",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","groupBy":[{"type":"TERMS","field":"state.secret","alias":"secret"}],"metrics":[$COUNT_METRIC]"""
                ),
            ),
            Case(
                "admission.date-histogram-not-temporal",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","groupBy":[{"type":"DATE_HISTOGRAM","field":"state.name","alias":"day","unit":"DAY"}],"metrics":[$COUNT_METRIC]"""
                ),
            ),
            Case(
                "admission.metric-filter-array-field",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","metrics":[{"type":"COUNT","alias":"tagged","filter":{"op":"EQ","field":"state.tags","value":"t"}}]"""
                ),
            ),
            Case(
                "admission.single-unknown-field",
                Route.SNAPSHOT_SINGLE,
                """{"filter":{"op":"EQ","field":"state.missing","value":"x"}}"""
            ),
            Case(
                "admission.count-unknown-field",
                Route.SNAPSHOT_COUNT,
                """{"op":"EQ","field":"state.missing","value":"x"}"""
            ),
            Case(
                "event.projection-drops-body-type",
                Route.EVENT_LIST,
                list(ALL, ""","projection":{"include":["body.body"]}""")
            ),
            Case("event.unknown-field", Route.EVENT_COUNT, """{"op":"EQ","field":"body.missing","value":"x"}"""),
            Case(
                "event.cursor-sort-unsupported",
                Route.EVENT_CURSOR,
                """{"filter":$ALL,"size":10,"sort":[{"field":"body.bodyType","direction":"ASC"}]}"""
            ),
            // Error priority when several rules are violated at once
            Case(
                "priority.budget-before-schema",
                Route.SNAPSHOT_LIST,
                """{"filter":{"op":"EQ","field":"state.missing","value":"x"},"limit":1001}"""
            ),
            Case("priority.decode-before-budget", Route.SNAPSHOT_LIST, """{"filter":{"op":"NOPE"},"limit":1001}"""),
            Case(
                "priority.gating-before-schema",
                Route.SNAPSHOT_LIST,
                list("""{"op":"CONTAINS","field":"state.missing","value":"a"}"""),
                Guard.STRICT
            ),
        )

        val IDENTITY = setOf(
            QueryCapability.EXACT_MATCH,
            QueryCapability.PRESENCE,
            QueryCapability.SORT,
            QueryCapability.CURSOR_SORT,
        )

        val SNAPSHOT_SCHEMA = schema(
            QueryModel.SNAPSHOT,
            listOf(
                Field("aggregateId", string(), IDENTITY),
                Field("tenantId", string(), IDENTITY),
                Field("ownerId", string(), IDENTITY),
                Field("spaceId", string(), IDENTITY),
                Field(
                    "deleted",
                    scalar(QueryValueType.BOOLEAN),
                    setOf(QueryCapability.EXACT_MATCH, QueryCapability.PRESENCE)
                ),
                Field(
                    "state.name",
                    string(),
                    setOf(
                        QueryCapability.EXACT_MATCH,
                        QueryCapability.LITERAL_MATCH,
                        QueryCapability.PRESENCE,
                        QueryCapability.SORT,
                        QueryCapability.AGGREGATE_TERMS,
                    ),
                ),
                Field(
                    "state.amount",
                    scalar(QueryValueType.INTEGER),
                    setOf(
                        QueryCapability.EXACT_MATCH,
                        QueryCapability.RANGE,
                        QueryCapability.PRESENCE,
                        QueryCapability.SORT,
                        QueryCapability.CURSOR_SORT,
                        QueryCapability.AGGREGATE_NUMERIC,
                        QueryCapability.AGGREGATE_TERMS,
                    ),
                ),
                Field(
                    "state.createdAt",
                    scalar(QueryValueType.INTEGER, Temporal.Epoch()),
                    setOf(QueryCapability.RANGE, QueryCapability.SORT, QueryCapability.AGGREGATE_TEMPORAL),
                ),
                Field("state.note", string(), setOf(QueryCapability.PRESENCE)),
                Field("state.tags", array(string()), setOf(QueryCapability.EXACT_MATCH, QueryCapability.PRESENCE)),
                Field(
                    "state.secret",
                    string(maskRule()),
                    setOf(QueryCapability.EXACT_MATCH, QueryCapability.AGGREGATE_TERMS),
                ),
                Field("state.items", array(obj("sku" to string())), setOf(QueryCapability.ELEMENT_SCOPE)),
                Field("state.items[].sku", string(), setOf(QueryCapability.EXACT_MATCH)),
            ),
        )

        val EVENT_STREAM_SCHEMA = schema(
            QueryModel.EVENT_STREAM,
            listOf(
                Field("id", string(), IDENTITY),
                Field("aggregateId", string(), IDENTITY),
                Field("tenantId", string(), IDENTITY),
                Field("ownerId", string(), IDENTITY),
                Field("spaceId", string(), IDENTITY),
                Field(
                    "body",
                    array(obj("bodyType" to string(), "body" to obj("amount" to scalar(QueryValueType.INTEGER)))),
                    setOf(QueryCapability.ELEMENT_SCOPE),
                ),
                Field("body[].bodyType", string(), setOf(QueryCapability.EXACT_MATCH, QueryCapability.SORT)),
                Field("body[].body", obj("amount" to scalar(QueryValueType.INTEGER)), emptySet()),
                Field("body[].body.amount", scalar(QueryValueType.INTEGER), setOf(QueryCapability.RANGE)),
            ),
        )

        private data class Field(val path: String, val value: QueryValueSchema, val capabilities: Set<QueryCapability>)

        private fun scalar(type: QueryValueType, temporal: Temporal? = null, mask: MaskRule? = null) =
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(type),
                nullable = false,
                semanticType = temporal,
                maskRule = mask
            )

        private fun string(mask: MaskRule? = null) = scalar(QueryValueType.STRING, mask = mask)

        private fun array(items: QueryValueSchema) = QueryValueSchema(QueryValueKind.ARRAY, items = items)

        private fun obj(vararg properties: Pair<String, QueryValueSchema>) =
            QueryValueSchema(QueryValueKind.OBJECT, properties = properties.toMap())

        private fun maskRule(): MaskRule {
            val annotation = Masked::value.javaField!!.getAnnotation(Mask::class.java)
            return MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
        }

        /** Builds a nested logical schema from dotted paths (`[]` marks array items) and binds each path to itself. */
        private fun schema(model: QueryModel, fields: List<Field>): QueryModelSchema {
            val top = fields.filter { '.' !in it.path && "[]" !in it.path }
            val nested = fields.filter { it.path.startsWith("state.") }.filter { it.path.count { c -> c == '.' } == 1 }
            val properties = top.associate { it.path to it.value }.toMutableMap()
            if (nested.isNotEmpty()) {
                properties["state"] = obj(*nested.map { it.path.removePrefix("state.") to it.value }.toTypedArray())
            }
            val definition = LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = properties))
            val capabilitiesByPath = fields.associate { it.path to it.capabilities }
            val bindings = definition.values.keys.filter { it.segments.isNotEmpty() }.associateWith { path ->
                val capabilities = capabilitiesByPath[path.dotted()].orEmpty()
                QueryValueBindings(capabilities.associateWith { QueryFieldBindingTemplate(path, null) }, path, path)
            }
            return QueryModelSchema(model, emptySet(), definition, bindings)
        }

        private fun QueryPathTemplate.dotted(): String = segments.joinToString(".") {
            when (it) {
                is QueryPathSegment.Property -> it.name
                QueryPathSegment.Item -> "[]"
                is QueryPathSegment.Key -> "{}"
            }
        }.replace(".[]", "[]")

        private fun QueryModelSchema.asProvider(): QueryModelSchemaProvider = object : QueryModelSchemaProvider {
            override fun schema() = reactor.core.publisher.Mono.just(this@asProvider)
            override fun refresh() = schema()
        }
    }
}
