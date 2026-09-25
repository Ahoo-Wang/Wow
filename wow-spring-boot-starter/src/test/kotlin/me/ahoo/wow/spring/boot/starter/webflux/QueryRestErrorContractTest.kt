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
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryEntryPolicy
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
import me.ahoo.wow.webflux.exception.DefaultGlobalExceptionHandler
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.query.DefaultQueryRequestScope
import me.ahoo.wow.webflux.route.query.HttpQueryGuard
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.HandlerStrategies
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
                val client = clients.getValue(case.guard)
                val request = if (case.route.method == Https.Method.GET) {
                    client.get().uri(case.uri).accept(MediaType.APPLICATION_JSON)
                } else {
                    client.post().uri(case.uri)
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .bodyValue(case.body)
                }
                val response = request.exchange().expectBody(String::class.java).returnResult()
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

    private fun withClients(verify: (Map<Guard, WebTestClient>) -> Unit) = withClients(
        Guard.entries,
        emptyMap(),
        verify
    )

    /** One application context per guard, since the HTTP budget belongs to the gateways' entry policy. */
    private fun withClients(
        remaining: List<Guard>,
        clients: Map<Guard, WebTestClient>,
        verify: (Map<Guard, WebTestClient>) -> Unit,
    ) {
        if (remaining.isEmpty()) {
            verify(clients)
            return
        }
        val guard = remaining.first()
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
            .withBean(QueryEntryPolicy::class.java, { guard.policy })
            .run { context ->
                context.assert().hasNotFailed()
                val client = run {
                    val module = WebFluxAutoConfiguration().queryRouteModule(
                        context,
                        snapshotFactory,
                        eventFactory,
                        DefaultQueryRequestScope,
                        WebFluxRequestExceptionHandler(),
                        HttpQueryGuard(guard.policy.http, strictCountFilter = guard.strictCountFilter),
                    )
                    val router = RouterFunctions.route()
                    Route.entries.forEach { route ->
                        val contract = HttpRouteContract(
                            routeId = route.handlerKey,
                            method = route.method,
                            path = route.template,
                            handlerKey = route.handlerKey,
                            handlerMetadata = HttpRouteHandlerMetadata.Aggregate(metadata),
                        )
                        val handler = module.httpFactories.single { it.handlerKey == route.handlerKey }.create(contract)
                        if (route.method == Https.Method.GET) {
                            router.GET(route.template, handler)
                        } else {
                            router.POST(route.template, handler)
                        }
                    }
                    // The global handler renders errors a route throws before it builds its reactive response.
                    val strategies = HandlerStrategies.builder().exceptionHandler(
                        DefaultGlobalExceptionHandler()
                    ).build()
                    WebTestClient.bindToRouterFunction(router.build()).handlerStrategies(strategies).build()
                }
                withClients(remaining.drop(1), clients + (guard to client), verify)
            }
    }

    private class EmptyBackend(namedAggregate: NamedAggregate) :
        SnapshotQueryBackend by NoOpSnapshotQueryBackend(namedAggregate), EventStreamQueryBackend

    private enum class Guard(val policy: QueryEntryPolicy, val strictCountFilter: Boolean = false) {
        DEFAULT(QueryEntryPolicy()),
        STRICT(QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, allowExpensiveOperators = false))),
        STRICT_COUNT_FILTER(QueryEntryPolicy(), strictCountFilter = true),
        AUTHENTICATED_SCOPE(QueryEntryPolicy(requireAuthenticatedScope = true)),
    }

    private enum class Route(val handlerKey: String, val method: String = Https.Method.POST, pathVariables: String = "") {
        SNAPSHOT_SINGLE(BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE),
        SNAPSHOT_LIST(BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY),
        SNAPSHOT_PAGED(BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY),
        SNAPSHOT_CURSOR(BuiltInHttpRouteHandlerKeys.Snapshot.CURSOR_QUERY),
        SNAPSHOT_COUNT(BuiltInHttpRouteHandlerKeys.Snapshot.COUNT),
        SNAPSHOT_AGGREGATION(BuiltInHttpRouteHandlerKeys.Snapshot.AGGREGATION),
        EVENT_LIST(BuiltInHttpRouteHandlerKeys.Event.LIST_QUERY),
        EVENT_CURSOR(BuiltInHttpRouteHandlerKeys.Event.CURSOR_QUERY),
        EVENT_COUNT(BuiltInHttpRouteHandlerKeys.Event.COUNT),
        SNAPSHOT_SINGLE_STATE(BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE_STATE),
        SNAPSHOT_LIST_STATE(BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY_STATE),
        SNAPSHOT_PAGED_STATE(BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY_STATE),
        SNAPSHOT_CURSOR_STATE(BuiltInHttpRouteHandlerKeys.Snapshot.CURSOR_QUERY_STATE),
        SNAPSHOT_LOAD(BuiltInHttpRouteHandlerKeys.Snapshot.LOAD, Https.Method.GET, "/{id}"),
        EVENT_PAGED(BuiltInHttpRouteHandlerKeys.Event.PAGED_QUERY),
        EVENT_AGGREGATION(BuiltInHttpRouteHandlerKeys.Event.AGGREGATION),
        EVENT_LOAD(BuiltInHttpRouteHandlerKeys.Event.LOAD, Https.Method.GET, "/{id}/{headVersion}/{tailVersion}"),
        ;

        val path = "/$handlerKey"
        val template = path + pathVariables
    }

    private data class Case(
        val name: String,
        val route: Route,
        val body: String,
        val guard: Guard = Guard.DEFAULT,
        val uri: String = route.path,
    )

    private class Masked(@field:Mask val value: String)

    /** The case table: one entry per REST-reachable query error text, grouped by the layer that rejects it. */
    @Suppress("LargeClass")
    private companion object {
        const val UPDATE_ENV = "WOW_GOLDEN_UPDATE"
        val GOLDEN: Path = Path.of("src/test/resources/golden/query-rest-error-contract.json")

        const val ALL = """{"op":"MATCH_ALL"}"""
        const val COUNT_METRIC = """{"type":"COUNT","alias":"count"}"""

        fun list(filter: String, extra: String = "") = """{"filter":$filter,"limit":10$extra}"""
        fun aggregation(extra: String) = """{"filter":$ALL$extra}"""

        const val NAME_GROUP = """{"type":"TERMS","field":"state.name","alias":"name"}"""
        const val AMOUNT = """{"type":"FIELD","field":"state.amount"}"""
        const val ONE = """{"type":"CONSTANT","value":1}"""
        const val COUNT_REF = """{"type":"METRIC_REF","metric":"count"}"""
        const val HAVING_COUNT = """{"type":"CONDITION","metric":"count","operator":"GT","value":1}"""

        fun condition(condition: String) = """{"condition":$condition,"limit":10}"""
        fun cursor(filter: String, extra: String = "") = """{"filter":$filter,"size":10$extra}"""
        fun grouped(extra: String) = aggregation(""","groupBy":[$NAME_GROUP],"metrics":[$COUNT_METRIC]$extra""")
        fun metrics(vararg metrics: String) = aggregation(""","metrics":[${metrics.joinToString(",")}]""")
        fun joined(times: Int, item: (Int) -> String) = (1..times).joinToString(",", transform = item)
        fun sorts(fields: List<String>) = fields.joinToString(",") { """{"field":"$it","direction":"ASC"}""" }
        fun names(times: Int) = joined(times) { """{"op":"EQ","field":"state.name","value":"n$it"}""" }
        fun strings(times: Int) = joined(times) { "\"v$it\"" }
        fun numeric(alias: String, expression: String) =
            """{"type":"NUMERIC","function":"SUM","alias":"$alias","expression":$expression}"""
        fun derived(
            alias: String,
            expression: String
        ) = """{"type":"DERIVED","alias":"$alias","expression":$expression}"""
        fun add(left: String, right: String) = """{"type":"BINARY","operator":"ADD","left":$left,"right":$right}"""

        /** A left-deep expression of [depth] levels: each level adds [constant] to the level below. */
        fun chain(depth: Int, leaf: String, constant: String): String =
            if (depth == 1) leaf else add(chain(depth - 1, leaf, constant), constant)

        /** A complete binary expression of [depth] levels (2^depth - 1 nodes). */
        fun tree(
            depth: Int,
            leaf: String
        ): String = if (depth == 1) leaf else add(tree(depth - 1, leaf), tree(depth - 1, leaf))

        fun havingChain(depth: Int): String =
            if (depth == 1) HAVING_COUNT else """{"type":"AND","operands":[${havingChain(depth - 1)}]}"""

        val BASELINE_CASES = listOf(
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
                "strict-count-filter.root-without-op",
                Route.SNAPSHOT_COUNT,
                """{"field":"state.name","value":"x"}""",
                Guard.STRICT_COUNT_FILTER,
            ),
            Case(
                "strict-count-filter.event-root-without-op",
                Route.EVENT_COUNT,
                """{"field":"aggregateId","value":"x"}""",
                Guard.STRICT_COUNT_FILTER,
            ),
            Case(
                "authenticated-scope.snapshot-without-scope",
                Route.SNAPSHOT_LIST,
                list(ALL),
                Guard.AUTHENTICATED_SCOPE,
            ),
            Case("authenticated-scope.event-without-scope", Route.EVENT_COUNT, ALL, Guard.AUTHENTICATED_SCOPE),
            Case("strict-count-filter.op-accepted", Route.SNAPSHOT_COUNT, ALL, Guard.STRICT_COUNT_FILTER),
            Case(
                "strict-count-filter.legacy-operator-accepted",
                Route.SNAPSHOT_COUNT,
                """{"operator":"ALL"}""",
                Guard.STRICT_COUNT_FILTER,
            ),
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

        /** Canonical filter decoding: every `require` of the filter model, masked or leaked depending on the route. */
        val FILTER_DECODE_CASES = listOf(
            Case("decode.body-not-object", Route.SNAPSHOT_LIST, "[]"),
            Case("decode.empty-body", Route.SNAPSHOT_LIST, ""),
            Case(
                "decode.invalid-field-path",
                Route.SNAPSHOT_LIST,
                list("""{"op":"EQ","field":"bad field","value":1}""")
            ),
            Case(
                "decode.op-and-operator",
                Route.SNAPSHOT_LIST,
                list("""{"op":"EQ","operator":"EQ","field":"state.name","value":"x"}""")
            ),
            Case("decode.filter-property-without-op", Route.SNAPSHOT_LIST, list("""{"field":"state.name"}""")),
            Case("decode.and-empty-operands", Route.SNAPSHOT_LIST, list("""{"op":"AND","operands":[]}""")),
            Case("decode.or-empty-operands", Route.SNAPSHOT_LIST, list("""{"op":"OR","operands":[]}""")),
            Case("decode.nor-empty-operands", Route.SNAPSHOT_LIST, list("""{"op":"NOR","operands":[]}""")),
            Case(
                "decode.element-match-root-filter",
                Route.SNAPSHOT_LIST,
                list("""{"op":"ELEMENT_MATCH","field":"state.items","predicate":{"op":"TENANT_ID","value":"t"}}"""),
            ),
            Case("decode.search-blank-query", Route.SNAPSHOT_LIST, list("""{"op":"SEARCH","query":" "}""")),
            Case("decode.deletion-unknown-state", Route.SNAPSHOT_LIST, list("""{"op":"DELETION","state":"NOPE"}""")),
            Case(
                "decode.comparison-object-value",
                Route.SNAPSHOT_LIST,
                list("""{"op":"GT","field":"state.amount","value":{}}""")
            ),
            Case(
                "decode.comparison-null-value",
                Route.SNAPSHOT_LIST,
                list("""{"op":"GT","field":"state.amount","value":null}""")
            ),
            Case(
                "decode.equality-object-value",
                Route.SNAPSHOT_LIST,
                list("""{"op":"EQ","field":"state.name","value":{}}""")
            ),
            Case(
                "decode.equality-array-object-element",
                Route.SNAPSHOT_LIST,
                list("""{"op":"NE","field":"state.name","value":[{}]}""")
            ),
            Case(
                "decode.in-empty-values",
                Route.SNAPSHOT_LIST,
                list("""{"op":"IN","field":"state.name","values":[]}""")
            ),
            Case(
                "decode.in-null-value",
                Route.SNAPSHOT_LIST,
                list("""{"op":"IN","field":"state.name","values":[null]}""")
            ),
            Case(
                "decode.not-in-empty-values",
                Route.SNAPSHOT_LIST,
                list("""{"op":"NOT_IN","field":"state.name","values":[]}""")
            ),
            Case(
                "decode.contains-all-empty-values",
                Route.SNAPSHOT_LIST,
                list("""{"op":"CONTAINS_ALL","field":"state.tags","values":[]}""")
            ),
            Case(
                "decode.between-null-bound",
                Route.SNAPSHOT_LIST,
                list("""{"op":"BETWEEN","field":"state.amount","lowerBound":null,"upperBound":1}""")
            ),
            Case("decode.ids-empty-values", Route.SNAPSHOT_LIST, list("""{"op":"IDS","values":[]}""")),
            Case(
                "decode.aggregate-ids-empty-values",
                Route.SNAPSHOT_LIST,
                list("""{"op":"AGGREGATE_IDS","values":[]}""")
            ),
            Case(
                "decode.relative-time-blank-zone",
                Route.SNAPSHOT_LIST,
                list("""{"op":"TODAY","field":"state.createdAt","zoneId":" "}""")
            ),
            Case(
                "decode.relative-time-unknown-zone",
                Route.SNAPSHOT_LIST,
                list("""{"op":"TODAY","field":"state.createdAt","zoneId":"Nowhere/Zone"}""")
            ),
            Case(
                "decode.relative-time-blank-pattern",
                Route.SNAPSHOT_LIST,
                list("""{"op":"TODAY","field":"state.createdAt","datePattern":" "}""")
            ),
            Case(
                "decode.relative-time-invalid-pattern",
                Route.SNAPSHOT_LIST,
                list("""{"op":"TODAY","field":"state.createdAt","datePattern":"{"}""")
            ),
            Case(
                "decode.before-today-invalid-time",
                Route.SNAPSHOT_LIST,
                list("""{"op":"BEFORE_TODAY","field":"state.createdAt","time":"25:00"}""")
            ),
            Case(
                "decode.recent-days-zero",
                Route.SNAPSHOT_LIST,
                list("""{"op":"RECENT_DAYS","field":"state.createdAt","days":0}""")
            ),
            Case(
                "decode.earlier-days-zero",
                Route.SNAPSHOT_LIST,
                list("""{"op":"EARLIER_DAYS","field":"state.createdAt","days":0}""")
            ),
            // The COUNT body is a bare filter, decoded at the root where the filter rules throw outside Jackson.
            Case(
                "decode.count-op-and-operator",
                Route.SNAPSHOT_COUNT,
                """{"op":"EQ","operator":"EQ","field":"state.name","value":"x"}"""
            ),
            Case(
                "decode.count-nested-without-op",
                Route.SNAPSHOT_COUNT,
                """{"op":"AND","operands":[{"field":"state.name"}]}"""
            ),
            Case(
                "decode.count-equality-array-value",
                Route.SNAPSHOT_COUNT,
                """{"op":"EQ","field":"state.name","value":[1]}"""
            ),
            Case("decode.count-and-empty-operands", Route.SNAPSHOT_COUNT, """{"op":"AND","operands":[]}"""),
            Case("decode.count-unknown-operator", Route.SNAPSHOT_COUNT, """{"op":"NOPE"}"""),
            Case(
                "decode.count-invalid-field-path",
                Route.SNAPSHOT_COUNT,
                """{"op":"EQ","field":"bad field","value":1}"""
            ),
            // Other query bodies
            Case("decode.single-missing-filter", Route.SNAPSHOT_SINGLE, "{}"),
            Case("decode.paged-unknown-property", Route.SNAPSHOT_PAGED, """{"filter":$ALL,"extra":1}"""),
            Case("decode.cursor-size-zero", Route.SNAPSHOT_CURSOR, """{"filter":$ALL,"size":0}"""),
            Case(
                "decode.cursor-sort-too-many",
                Route.SNAPSHOT_CURSOR,
                cursor(ALL, ""","sort":[${sorts((1..33).map { "state.f$it" })}]"""),
            ),
            Case(
                "decode.cursor-condition-unsupported",
                Route.SNAPSHOT_CURSOR,
                """{"condition":{"operator":"ALL"},"size":10}"""
            ),
        )

        /** The deprecated `condition` body (REST) and its conversion to a filter expression. */
        val LEGACY_DECODE_CASES = listOf(
            Case("decode.condition-null", Route.SNAPSHOT_LIST, condition("null")),
            Case("decode.condition-op-and-operator", Route.SNAPSHOT_LIST, condition("""{"op":"EQ","operator":"EQ"}""")),
            Case("decode.legacy-unknown-operator", Route.SNAPSHOT_LIST, condition("""{"operator":"NOPE"}""")),
            Case(
                "decode.legacy-and-empty-children",
                Route.SNAPSHOT_LIST,
                condition("""{"operator":"AND","children":[]}""")
            ),
            Case(
                "decode.legacy-between-bounds",
                Route.SNAPSHOT_LIST,
                condition("""{"operator":"BETWEEN","field":"state.amount","value":[1]}""")
            ),
            Case(
                "decode.legacy-before-today-value-type",
                Route.SNAPSHOT_LIST,
                condition("""{"operator":"BEFORE_TODAY","field":"state.createdAt","value":true}""")
            ),
            Case(
                "decode.legacy-before-today-invalid-time",
                Route.SNAPSHOT_LIST,
                condition("""{"operator":"BEFORE_TODAY","field":"state.createdAt","value":"25:00"}""")
            ),
            Case(
                "decode.legacy-recent-days-not-number",
                Route.SNAPSHOT_LIST,
                condition("""{"operator":"RECENT_DAYS","field":"state.createdAt","value":"x"}""")
            ),
            Case(
                "decode.legacy-earlier-days-not-number",
                Route.SNAPSHOT_LIST,
                condition("""{"operator":"EARLIER_DAYS","field":"state.createdAt","value":"x"}""")
            ),
            Case(
                "decode.legacy-recent-days-zero",
                Route.SNAPSHOT_LIST,
                condition("""{"operator":"RECENT_DAYS","field":"state.createdAt","value":0}""")
            ),
            Case(
                "decode.legacy-deleted-value-type",
                Route.SNAPSHOT_LIST,
                condition("""{"operator":"DELETED","value":1}""")
            ),
            Case(
                "decode.legacy-deleted-unknown-state",
                Route.SNAPSHOT_LIST,
                condition("""{"operator":"DELETED","value":"nope"}""")
            ),
            Case("decode.legacy-id-not-string", Route.SNAPSHOT_LIST, condition("""{"operator":"ID","value":1}""")),
            Case("decode.legacy-invalid-field", Route.SNAPSHOT_LIST, condition("""{"operator":"EQ","value":1}""")),
            Case(
                "decode.legacy-unknown-zone",
                Route.SNAPSHOT_LIST,
                condition("""{"operator":"TODAY","field":"state.createdAt","options":{"zoneId":"Nowhere/Zone"}}""")
            ),
            Case(
                "decode.legacy-invalid-date-pattern",
                Route.SNAPSHOT_LIST,
                condition("""{"operator":"TODAY","field":"state.createdAt","options":{"datePattern":"{"}}""")
            ),
            Case(
                "decode.legacy-in-null-value",
                Route.SNAPSHOT_LIST,
                condition("""{"operator":"IN","field":"state.name","value":[null]}""")
            ),
            Case("decode.legacy-search-blank", Route.SNAPSHOT_LIST, condition("""{"operator":"MATCH","value":" "}""")),
            Case(
                "decode.legacy-element-match-root-filter",
                Route.SNAPSHOT_LIST,
                condition(
                    """{"operator":"ELEM_MATCH","field":"state.items","children":[{"operator":"TENANT_ID","value":"t"}]}"""
                ),
            ),
            Case(
                "decode.legacy-element-match-empty-children",
                Route.SNAPSHOT_LIST,
                condition("""{"operator":"ELEM_MATCH","field":"state.items","children":[]}""")
            ),
            // A bare legacy condition as the COUNT body converts outside the query-body deserializer.
            Case(
                "decode.count-legacy-and-empty-children",
                Route.SNAPSHOT_COUNT,
                """{"operator":"AND","children":[]}"""
            ),
            Case(
                "decode.count-legacy-between-bounds",
                Route.SNAPSHOT_COUNT,
                """{"operator":"BETWEEN","field":"state.amount","value":[1]}"""
            ),
            Case(
                "decode.count-legacy-before-today-value-type",
                Route.SNAPSHOT_COUNT,
                """{"operator":"BEFORE_TODAY","field":"state.createdAt","value":true}"""
            ),
            Case(
                "decode.count-legacy-before-today-invalid-time",
                Route.SNAPSHOT_COUNT,
                """{"operator":"BEFORE_TODAY","field":"state.createdAt","value":"25:00"}"""
            ),
            Case(
                "decode.count-legacy-recent-days-not-number",
                Route.SNAPSHOT_COUNT,
                """{"operator":"RECENT_DAYS","field":"state.createdAt","value":"x"}"""
            ),
            Case(
                "decode.count-legacy-earlier-days-not-number",
                Route.SNAPSHOT_COUNT,
                """{"operator":"EARLIER_DAYS","field":"state.createdAt","value":"x"}"""
            ),
            Case(
                "decode.count-legacy-recent-days-zero",
                Route.SNAPSHOT_COUNT,
                """{"operator":"RECENT_DAYS","field":"state.createdAt","value":0}"""
            ),
            Case(
                "decode.count-legacy-deleted-value-type",
                Route.SNAPSHOT_COUNT,
                """{"operator":"DELETED","value":1}"""
            ),
            Case(
                "decode.count-legacy-deleted-unknown-state",
                Route.SNAPSHOT_COUNT,
                """{"operator":"DELETED","value":"nope"}"""
            ),
            Case("decode.count-legacy-id-not-string", Route.SNAPSHOT_COUNT, """{"operator":"ID","value":1}"""),
            Case("decode.count-legacy-invalid-field", Route.SNAPSHOT_COUNT, """{"operator":"EQ","value":1}"""),
            Case(
                "decode.count-legacy-unknown-zone",
                Route.SNAPSHOT_COUNT,
                """{"operator":"TODAY","field":"state.createdAt","options":{"zoneId":"Nowhere/Zone"}}"""
            ),
            Case(
                "decode.count-legacy-invalid-date-pattern",
                Route.SNAPSHOT_COUNT,
                """{"operator":"TODAY","field":"state.createdAt","options":{"datePattern":"{"}}"""
            ),
            Case(
                "decode.count-legacy-in-null-value",
                Route.SNAPSHOT_COUNT,
                """{"operator":"IN","field":"state.name","value":[null]}"""
            ),
            Case("decode.count-legacy-search-blank", Route.SNAPSHOT_COUNT, """{"operator":"MATCH","value":" "}"""),
            Case(
                "decode.count-legacy-element-match-root-filter",
                Route.SNAPSHOT_COUNT,
                """{"operator":"ELEM_MATCH","field":"state.items","children":[{"operator":"TENANT_ID","value":"t"}]}""",
            ),
            Case("decode.count-legacy-unknown-operator", Route.SNAPSHOT_COUNT, """{"operator":"NOPE"}"""),
        )

        /** Aggregation body decoding: every `require` of the aggregation model. */
        val AGGREGATION_DECODE_CASES = listOf(
            Case(
                "decode.aggregation-filter-without-op",
                Route.SNAPSHOT_AGGREGATION,
                """{"filter":{"field":"state.name"},"metrics":[$COUNT_METRIC]}"""
            ),
            Case("decode.aggregation-missing-metrics", Route.SNAPSHOT_AGGREGATION, aggregation("")),
            Case("decode.aggregation-empty-metrics", Route.SNAPSHOT_AGGREGATION, metrics()),
            Case(
                "decode.aggregation-unknown-metric-type",
                Route.SNAPSHOT_AGGREGATION,
                metrics("""{"type":"NOPE","alias":"x"}""")
            ),
            Case(
                "decode.aggregation-elements-too-many",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","elements":[${joined(6) { """{"path":"state.items"}""" }}],"metrics":[$COUNT_METRIC]"""
                ),
            ),
            Case(
                "decode.aggregation-groups-too-many",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","groupBy":[${joined(
                        33
                    ) { """{"type":"TERMS","field":"state.name","alias":"g$it"}""" }}],"metrics":[$COUNT_METRIC]"""
                ),
            ),
            Case(
                "decode.aggregation-metrics-too-many",
                Route.SNAPSHOT_AGGREGATION,
                metrics(*(1..65).map { """{"type":"COUNT","alias":"c$it"}""" }.toTypedArray()),
            ),
            Case(
                "decode.aggregation-sort-too-many",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","sort":[${sorts((1..33).map { "s$it" })}]"""),
            ),
            Case("decode.aggregation-limit-zero", Route.SNAPSHOT_AGGREGATION, grouped(""","limit":0""")),
            Case("decode.aggregation-limit-above-model-max", Route.SNAPSHOT_AGGREGATION, grouped(""","limit":10001""")),
            Case(
                "decode.aggregation-sort-without-group",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(""","metrics":[$COUNT_METRIC],"sort":[${sorts(listOf("count"))}]"""),
            ),
            Case(
                "decode.aggregation-dense-with-other-groups",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","groupBy":[{"type":"DATE_HISTOGRAM","field":"state.createdAt","alias":"day","unit":"DAY","dense":true},$NAME_GROUP],"metrics":[$COUNT_METRIC]"""
                ),
            ),
            Case(
                "decode.aggregation-duplicate-alias",
                Route.SNAPSHOT_AGGREGATION,
                metrics(COUNT_METRIC, COUNT_METRIC)
            ),
            Case(
                "decode.aggregation-duplicate-sort",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","sort":[${sorts(listOf("name", "name"))}]""")
            ),
            Case(
                "decode.aggregation-sort-unknown-alias",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","sort":[${sorts(listOf("missing"))}]""")
            ),
            Case(
                "decode.aggregation-effective-sort-too-many",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","groupBy":[${joined(
                        32
                    ) { """{"type":"TERMS","field":"state.name","alias":"g$it"}""" }}],"metrics":[$COUNT_METRIC],"sort":[${sorts(
                        listOf("count")
                    )}]"""
                ),
            ),
            Case(
                "decode.aggregation-element-root-filter",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","elements":[{"path":"state.items","filter":{"op":"TENANT_ID","value":"t"}}],"metrics":[$COUNT_METRIC]"""
                ),
            ),
            Case(
                "decode.aggregation-terms-blank-missing-key",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","groupBy":[{"type":"TERMS","field":"state.name","alias":"name","missingKey":" "}],"metrics":[$COUNT_METRIC]"""
                ),
            ),
            Case(
                "decode.aggregation-histogram-interval-zero",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","groupBy":[{"type":"HISTOGRAM","field":"state.amount","alias":"bucket","interval":0}],"metrics":[$COUNT_METRIC]"""
                ),
            ),
            Case(
                "decode.aggregation-date-histogram-unknown-zone",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","groupBy":[{"type":"DATE_HISTOGRAM","field":"state.createdAt","alias":"day","unit":"DAY","timeZone":"Nowhere/Zone"}],"metrics":[$COUNT_METRIC]"""
                ),
            ),
            Case(
                "decode.aggregation-constant-not-finite",
                Route.SNAPSHOT_AGGREGATION,
                metrics(numeric("total", add(AMOUNT, """{"type":"CONSTANT","value":1e400}""")))
            ),
            Case(
                "decode.aggregation-percentile-out-of-range",
                Route.SNAPSHOT_AGGREGATION,
                metrics("""{"type":"PERCENTILE","alias":"p","percentile":100,"expression":$AMOUNT}""")
            ),
            Case(
                "decode.aggregation-derived-constant-not-finite",
                Route.SNAPSHOT_AGGREGATION,
                metrics(COUNT_METRIC, derived("ratio", add(COUNT_REF, """{"type":"CONSTANT","value":1e400}""")))
            ),
            Case(
                "decode.aggregation-alias-with-dot",
                Route.SNAPSHOT_AGGREGATION,
                metrics("""{"type":"COUNT","alias":"a.b"}""")
            ),
            Case(
                "decode.aggregation-alias-reserved-prefix",
                Route.SNAPSHOT_AGGREGATION,
                metrics("""{"type":"COUNT","alias":"__wow_count"}""")
            ),
            Case(
                "decode.aggregation-alias-invalid",
                Route.SNAPSHOT_AGGREGATION,
                metrics("""{"type":"COUNT","alias":"1a"}""")
            ),
            Case(
                "decode.aggregation-expression-too-deep",
                Route.SNAPSHOT_AGGREGATION,
                metrics(numeric("total", chain(9, AMOUNT, ONE)))
            ),
            Case(
                "decode.aggregation-expression-too-many-nodes",
                Route.SNAPSHOT_AGGREGATION,
                metrics(numeric("a", tree(8, AMOUNT)), numeric("b", tree(8, AMOUNT))),
            ),
            Case(
                "decode.aggregation-derived-too-deep",
                Route.SNAPSHOT_AGGREGATION,
                metrics(COUNT_METRIC, derived("ratio", chain(9, COUNT_REF, ONE)))
            ),
            Case(
                "decode.aggregation-derived-too-many-nodes",
                Route.SNAPSHOT_AGGREGATION,
                metrics(COUNT_METRIC, derived("a", tree(8, COUNT_REF)), derived("b", tree(8, COUNT_REF))),
            ),
            Case(
                "decode.aggregation-derived-undeclared-reference",
                Route.SNAPSHOT_AGGREGATION,
                metrics(derived("ratio", COUNT_REF), COUNT_METRIC)
            ),
            Case(
                "decode.aggregation-derived-any-reference",
                Route.SNAPSHOT_AGGREGATION,
                metrics(
                    """{"type":"ANY","field":"state.name","alias":"count"}""",
                    derived("ratio", COUNT_REF)
                ),
            ),
            Case(
                "decode.aggregation-having-without-group",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(""","metrics":[$COUNT_METRIC],"having":$HAVING_COUNT"""),
            ),
            Case(
                "decode.aggregation-having-too-deep",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","having":${havingChain(9)}""")
            ),
            Case(
                "decode.aggregation-having-value-not-finite",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","having":{"type":"CONDITION","metric":"count","operator":"GT","value":1e400}"""),
            ),
            Case(
                "decode.aggregation-having-between-not-finite",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","having":{"type":"BETWEEN","metric":"count","lower":0,"upper":1e400}"""),
            ),
            Case(
                "decode.aggregation-having-between-inverted",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","having":{"type":"BETWEEN","metric":"count","lower":2,"upper":1}"""),
            ),
            Case(
                "decode.aggregation-having-in-empty",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","having":{"type":"IN","metric":"count","values":[]}""")
            ),
            Case(
                "decode.aggregation-having-in-not-finite",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","having":{"type":"IN","metric":"count","values":[1e400]}""")
            ),
            Case(
                "decode.aggregation-having-and-empty",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","having":{"type":"AND","operands":[]}""")
            ),
            Case(
                "decode.aggregation-having-or-empty",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","having":{"type":"OR","operands":[]}""")
            ),
            Case(
                "decode.aggregation-having-unknown-metric",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","having":{"type":"IS_NULL","metric":"missing"}""")
            ),
            Case(
                "decode.aggregation-having-any-metric",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","groupBy":[$NAME_GROUP],"metrics":[{"type":"ANY","field":"state.amount","alias":"sample"}],"having":{"type":"IS_NULL","metric":"sample"}"""
                ),
            ),
        )

        val GUARD_CASES = listOf(
            Case("guard.list-limit-zero-uses-default", Route.SNAPSHOT_LIST, """{"filter":$ALL,"limit":0}"""),
            Case(
                "guard.page-size-zero",
                Route.SNAPSHOT_PAGED,
                """{"filter":$ALL,"pagination":{"index":1,"size":0}}"""
            ),
            Case(
                "guard.single-filter-nodes-too-many",
                Route.SNAPSHOT_SINGLE,
                """{"filter":{"op":"OR","operands":[${names(130)}]}}"""
            ),
            Case(
                "guard.count-filter-values-too-many",
                Route.SNAPSHOT_COUNT,
                """{"op":"IDS","values":[${strings(1001)}]}"""
            ),
            Case(
                "guard.aggregation-filter-nodes-too-many",
                Route.SNAPSHOT_AGGREGATION,
                """{"filter":{"op":"OR","operands":[${names(130)}]},"metrics":[$COUNT_METRIC]}""",
            ),
            Case(
                "guard.aggregation-metric-filter-values-too-many",
                Route.SNAPSHOT_AGGREGATION,
                metrics(
                    """{"type":"COUNT","alias":"c","filter":{"op":"NOT_IN","field":"state.name","values":[${strings(
                        1001
                    )}]}}"""
                ),
            ),
            Case(
                "guard.aggregation-having-nodes-too-many",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","having":{"type":"OR","operands":[${joined(128) { HAVING_COUNT }}]}"""),
            ),
            Case(
                "guard.aggregation-having-values-too-many",
                Route.SNAPSHOT_AGGREGATION,
                grouped(""","having":{"type":"IN","metric":"count","values":[${joined(1001) { "$it" }}]}"""),
            ),
            Case("guard.list-state-limit-too-large", Route.SNAPSHOT_LIST_STATE, """{"filter":$ALL,"limit":1001}"""),
            Case(
                "guard.paged-state-size-too-large",
                Route.SNAPSHOT_PAGED_STATE,
                """{"filter":$ALL,"pagination":{"index":1,"size":101}}"""
            ),
            Case("guard.cursor-state-size-too-large", Route.SNAPSHOT_CURSOR_STATE, """{"filter":$ALL,"size":101}"""),
            Case("guard.event-list-limit-too-large", Route.EVENT_LIST, """{"filter":$ALL,"limit":1001}"""),
            Case(
                "guard.event-paged-window-too-large",
                Route.EVENT_PAGED,
                """{"filter":$ALL,"pagination":{"index":101,"size":100}}"""
            ),
            Case("guard.event-cursor-size-too-large", Route.EVENT_CURSOR, """{"filter":$ALL,"size":101}"""),
            Case(
                "guard.event-aggregation-limit-too-large",
                Route.EVENT_AGGREGATION,
                aggregation(""","metrics":[$COUNT_METRIC],"limit":1001""")
            ),
            Case(
                "guard.event-load-range-too-large",
                Route.EVENT_LOAD,
                "",
                uri = "${Route.EVENT_LOAD.path}/order-1/1/1001"
            ),
            Case("guard.event-load-range-inverted", Route.EVENT_LOAD, "", uri = "${Route.EVENT_LOAD.path}/order-1/5/1"),
        )

        val STRICT_CASES = listOf(
            Case("strict.list-matches-all-accepted", Route.SNAPSHOT_LIST, list(ALL), Guard.STRICT),
            Case(
                "strict.paged-matches-all",
                Route.SNAPSHOT_PAGED,
                """{"filter":$ALL,"pagination":{"index":1,"size":10}}""",
                Guard.STRICT
            ),
            Case(
                "strict.count-deletion-all",
                Route.SNAPSHOT_COUNT,
                """{"op":"DELETION","state":"ALL"}""",
                Guard.STRICT
            ),
            Case("strict.event-count-matches-all", Route.EVENT_COUNT, ALL, Guard.STRICT),
            Case(
                "strict.starts-with-empty",
                Route.SNAPSHOT_LIST,
                list("""{"op":"STARTS_WITH","field":"state.name","value":""}"""),
                Guard.STRICT
            ),
            Case(
                "strict.starts-with-case-insensitive",
                Route.SNAPSHOT_LIST,
                list("""{"op":"STARTS_WITH","field":"state.name","value":"a","stringComparison":"CASE_INSENSITIVE"}"""),
                Guard.STRICT,
            ),
            Case(
                "strict.aggregation-filter-operator",
                Route.SNAPSHOT_AGGREGATION,
                """{"filter":{"op":"NE","field":"state.name","value":"a"},"metrics":[$COUNT_METRIC]}""",
                Guard.STRICT,
            ),
            Case(
                "strict.derived-metric",
                Route.SNAPSHOT_AGGREGATION,
                metrics(COUNT_METRIC, derived("double", add(COUNT_REF, COUNT_REF))),
                Guard.STRICT
            ),
        )

        val ADMISSION_CASES = listOf(
            Case("admission.single-not-found", Route.SNAPSHOT_SINGLE, """{"filter":$ALL}"""),
            Case(
                "admission.snapshot-load-not-found",
                Route.SNAPSHOT_LOAD,
                "",
                uri = "${Route.SNAPSHOT_LOAD.path}/order-1"
            ),
            Case(
                "admission.search-field-unsupported",
                Route.SNAPSHOT_LIST,
                list("""{"op":"SEARCH","query":"x","fields":["state.name"]}""")
            ),
            Case(
                "admission.search-phrase-unsupported",
                Route.SNAPSHOT_LIST,
                list("""{"op":"SEARCH","query":"x","fields":["state.text"],"mode":"PHRASE"}""")
            ),
            Case(
                "admission.in-value-type-mismatch",
                Route.SNAPSHOT_LIST,
                list("""{"op":"IN","field":"state.amount","values":["a"]}""")
            ),
            Case(
                "admission.contains-all-not-collection",
                Route.SNAPSHOT_LIST,
                list("""{"op":"CONTAINS_ALL","field":"state.name","values":["a"]}""")
            ),
            Case(
                "admission.is-empty-string-not-string",
                Route.SNAPSHOT_LIST,
                list("""{"op":"IS_EMPTY_STRING","field":"state.amount"}""")
            ),
            Case(
                "admission.relative-time-pattern-conflict",
                Route.SNAPSHOT_LIST,
                list("""{"op":"TODAY","field":"state.createdAt","datePattern":"yyyy-MM-dd"}""")
            ),
            Case(
                "admission.element-match-scoped-unknown-field",
                Route.SNAPSHOT_LIST,
                list(
                    """{"op":"ELEMENT_MATCH","field":"state.items","predicate":{"op":"EQ","field":"missing","value":"x"}}"""
                ),
            ),
            Case(
                "admission.projection-exclude-unknown-field",
                Route.SNAPSHOT_LIST,
                list(ALL, ""","projection":{"exclude":["state.missing"]}""")
            ),
            Case(
                "admission.cursor-field-not-allowed",
                Route.SNAPSHOT_CURSOR,
                cursor(ALL, ""","sort":[${sorts(listOf("state.labels"))}]""")
            ),
            Case(
                "admission.cursor-duplicate-sort",
                Route.SNAPSHOT_CURSOR,
                cursor(
                    ALL,
                    ""","sort":[{"field":"aggregateId","direction":"ASC"},{"field":"aggregateId","direction":"DESC"}]"""
                ),
            ),
            Case(
                "admission.cursor-effective-sort-too-many",
                Route.SNAPSHOT_CURSOR,
                cursor(ALL, ""","sort":[${sorts((1..32).map { "state.f$it" })}]"""),
            ),
            Case(
                "admission.terms-missing-key-not-string",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","groupBy":[{"type":"TERMS","field":"state.amount","alias":"amount","missingKey":"none"}],"metrics":[$COUNT_METRIC]"""
                ),
            ),
            Case(
                "admission.any-multi-valued",
                Route.SNAPSHOT_AGGREGATION,
                metrics("""{"type":"ANY","field":"state.labels","alias":"label"}""")
            ),
            Case(
                "admission.distinct-count-unsupported",
                Route.SNAPSHOT_AGGREGATION,
                metrics(
                    """{"type":"DISTINCT_COUNT","alias":"notes","expression":{"type":"FIELD","field":"state.note"}}"""
                ),
            ),
            Case(
                "admission.numeric-metric-unsupported",
                Route.SNAPSHOT_AGGREGATION,
                metrics(numeric("total", """{"type":"FIELD","field":"state.name"}"""))
            ),
            Case(
                "admission.histogram-unsupported",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","groupBy":[{"type":"HISTOGRAM","field":"state.name","alias":"bucket","interval":1}],"metrics":[$COUNT_METRIC]"""
                ),
            ),
            Case(
                "admission.element-path-unsupported",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(""","elements":[{"path":"state.name"}],"metrics":[$COUNT_METRIC]"""),
            ),
            Case(
                "admission.aggregation-filter-unknown-field",
                Route.SNAPSHOT_AGGREGATION,
                """{"filter":{"op":"EQ","field":"state.missing","value":"x"},"metrics":[$COUNT_METRIC]}""",
            ),
            Case(
                "admission.metric-filter-search",
                Route.SNAPSHOT_AGGREGATION,
                metrics(
                    """{"type":"COUNT","alias":"hits","filter":{"op":"SEARCH","query":"x","fields":["state.text"]}}"""
                ),
            ),
            Case(
                "admission.metric-filter-element-match",
                Route.SNAPSHOT_AGGREGATION,
                metrics(
                    """{"type":"COUNT","alias":"hits","filter":{"op":"ELEMENT_MATCH","field":"state.items","predicate":{"op":"EQ","field":"sku","value":"x"}}}"""
                ),
            ),
            Case(
                "admission.paged-unknown-field",
                Route.SNAPSHOT_PAGED,
                """{"filter":{"op":"EQ","field":"state.missing","value":"x"}}"""
            ),
            Case(
                "admission.single-state-unknown-field",
                Route.SNAPSHOT_SINGLE_STATE,
                """{"filter":{"op":"EQ","field":"state.missing","value":"x"}}"""
            ),
            Case(
                "admission.list-state-unknown-field",
                Route.SNAPSHOT_LIST_STATE,
                list("""{"op":"EQ","field":"state.missing","value":"x"}""")
            ),
            Case(
                "admission.paged-state-unknown-field",
                Route.SNAPSHOT_PAGED_STATE,
                """{"filter":{"op":"EQ","field":"state.missing","value":"x"}}"""
            ),
            Case(
                "admission.cursor-state-unknown-field",
                Route.SNAPSHOT_CURSOR_STATE,
                cursor("""{"op":"EQ","field":"state.missing","value":"x"}""")
            ),
        )

        val EVENT_CASES = listOf(
            Case("event.load-accepted", Route.EVENT_LOAD, "", uri = "${Route.EVENT_LOAD.path}/order-1/1/10"),
            Case("event.load-version-not-integer", Route.EVENT_LOAD, "", uri = "${Route.EVENT_LOAD.path}/order-1/a/10"),
            Case(
                "event.projection-excludes-body-type",
                Route.EVENT_LIST,
                list(ALL, ""","projection":{"exclude":["body.bodyType"]}""")
            ),
            Case(
                "event.sort-unsupported",
                Route.EVENT_LIST,
                list(ALL, ""","sort":[${sorts(listOf("body.body.amount"))}]""")
            ),
            Case(
                "event.paged-unknown-field",
                Route.EVENT_PAGED,
                """{"filter":{"op":"EQ","field":"body.missing","value":"x"}}"""
            ),
            Case(
                "event.aggregation-unknown-field",
                Route.EVENT_AGGREGATION,
                aggregation(
                    ""","groupBy":[{"type":"TERMS","field":"body.missing","alias":"missing"}],"metrics":[$COUNT_METRIC]"""
                ),
            ),
        )

        val PRIORITY_CASES = listOf(
            Case("priority.decode-before-strict", Route.SNAPSHOT_COUNT, """{"op":"NOPE"}""", Guard.STRICT),
            Case(
                "priority.list-limit-before-gating",
                Route.SNAPSHOT_LIST,
                """{"filter":{"op":"CONTAINS","field":"state.name","value":"a"},"limit":1001}""",
                Guard.STRICT,
            ),
            Case(
                "priority.page-index-before-size",
                Route.SNAPSHOT_PAGED,
                """{"filter":$ALL,"pagination":{"index":0,"size":101}}"""
            ),
            Case(
                "priority.page-size-before-window",
                Route.SNAPSHOT_PAGED,
                """{"filter":$ALL,"pagination":{"index":101,"size":101}}"""
            ),
            Case(
                "priority.filter-traversal-values-first",
                Route.SNAPSHOT_LIST,
                list(
                    """{"op":"OR","operands":[${names(
                        130
                    )},{"op":"IN","field":"state.name","values":[${strings(1001)}]}]}"""
                ),
            ),
            Case(
                "priority.filter-traversal-nodes-first",
                Route.SNAPSHOT_LIST,
                list(
                    """{"op":"OR","operands":[{"op":"IN","field":"state.name","values":[${strings(
                        1001
                    )}]},${names(130)}]}"""
                ),
            ),
            Case(
                "priority.gating-before-count-matches-all",
                Route.SNAPSHOT_COUNT,
                """{"op":"OR","operands":[$ALL,{"op":"CONTAINS","field":"state.name","value":"a"}]}""",
                Guard.STRICT,
            ),
            Case(
                "priority.aggregation-limit-before-gating",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(""","elements":[{"path":"state.items"}],"metrics":[$COUNT_METRIC],"limit":1001"""),
                Guard.STRICT,
            ),
            Case(
                "priority.aggregation-elements-before-metric-sort",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","elements":[{"path":"state.items"}],"groupBy":[$NAME_GROUP],"metrics":[$COUNT_METRIC],"sort":[${sorts(
                        listOf("count")
                    )}]"""
                ),
                Guard.STRICT,
            ),
            Case(
                "priority.aggregation-filter-nodes-before-having",
                Route.SNAPSHOT_AGGREGATION,
                """{"filter":{"op":"OR","operands":[${names(
                    130
                )}]},"groupBy":[$NAME_GROUP],"metrics":[$COUNT_METRIC],"having":{"type":"IN","metric":"count","values":[${joined(
                    1001
                ) { "$it" }}]}}""",
            ),
            Case(
                "priority.filter-before-projection",
                Route.SNAPSHOT_LIST,
                list(
                    """{"op":"EQ","field":"state.missing","value":"x"}""",
                    ""","projection":{"include":["state.other"]}"""
                ),
            ),
            Case(
                "priority.projection-before-sort",
                Route.SNAPSHOT_LIST,
                list(ALL, ""","projection":{"include":["state.missing"]},"sort":[${sorts(listOf("state.note"))}]"""),
            ),
            Case(
                "priority.cursor-unique-sort-before-schema",
                Route.SNAPSHOT_CURSOR,
                cursor(
                    """{"op":"EQ","field":"state.missing","value":"x"}""",
                    ""","sort":[{"field":"aggregateId","direction":"ASC"},{"field":"aggregateId","direction":"DESC"}]""",
                ),
            ),
            Case(
                "priority.aggregation-filter-before-group",
                Route.SNAPSHOT_AGGREGATION,
                """{"filter":{"op":"EQ","field":"state.missing","value":"x"},"groupBy":[{"type":"TERMS","field":"state.other","alias":"other"}],"metrics":[$COUNT_METRIC]}""",
            ),
            Case(
                "priority.aggregation-group-before-metric",
                Route.SNAPSHOT_AGGREGATION,
                aggregation(
                    ""","groupBy":[{"type":"TERMS","field":"state.other","alias":"other"}],"metrics":[${numeric(
                        "total",
                        """{"type":"FIELD","field":"state.name"}"""
                    )}]"""
                ),
            ),
        )

        val CASES = BASELINE_CASES + FILTER_DECODE_CASES + LEGACY_DECODE_CASES + AGGREGATION_DECODE_CASES +
            GUARD_CASES + STRICT_CASES + ADMISSION_CASES + EVENT_CASES + PRIORITY_CASES

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
                Field(
                    "state.labels",
                    array(string()),
                    setOf(QueryCapability.EXACT_MATCH, QueryCapability.CURSOR_SORT, QueryCapability.AGGREGATE_TERMS),
                ),
                Field("state.text", string(), setOf(QueryCapability.FULL_TEXT_TERMS)),
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
                Field("version", scalar(QueryValueType.INTEGER), setOf(QueryCapability.RANGE, QueryCapability.SORT)),
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
