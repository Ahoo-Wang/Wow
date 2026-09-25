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

import io.mockk.mockk
import me.ahoo.cosid.machine.HostAddressSupplier
import me.ahoo.cosid.machine.LocalHostAddressSupplier
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.queryScope
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.spring.boot.starter.bi.BiScriptProperties
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.command.CommandGatewayAutoConfiguration
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.EventSourcingAutoConfiguration
import me.ahoo.wow.spring.boot.starter.modeling.AggregateAutoConfiguration
import me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfiguration
import me.ahoo.wow.spring.boot.starter.query.QueryAutoConfiguration
import me.ahoo.wow.spring.boot.starter.webflux.bi.BiDeploymentInspectorAutoConfiguration
import me.ahoo.wow.spring.boot.starter.webflux.route.QueryRouteModule
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrar
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.context.ContextView
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Route contract for the built-in REST query routes of an aggregate, driven through the application's real router
 * (`commandRouterFunction`, built by `RouterFunctionBuilder` from the real `RouterSpecs` catalog and route modules).
 *
 * - The inventory of query routes (route id, method, path, handler key) is locked in [GOLDEN]; a deliberate change is
 *   recorded by rerunning with `WOW_GOLDEN_UPDATE=true` and reviewing the diff.
 * - Every query route that reaches a query gateway must run the gateway call in the HTTP query context. Each backend
 *   call is recorded as a [BackendCall] carrying the Reactor context it ran in, and [assertRanInHttpQueryContext] is
 *   the single place that states what that context must contain; a later step adds the `Entry = HTTP` marker
 *   assertion there.
 */
class QueryRouteContractTest {
    @Test
    fun `query route inventory is locked and matches the query route module`() {
        withRouter { context, _, _ ->
            val module = context.getBean(QueryRouteModule::class.java)
            val moduleKeys = module.httpFactories.map { it.handlerKey }.toSet()
            val routes = orderRoutes(context)
            val queryRoutes = routes.filter { it.handlerKey in moduleKeys }

            // No query factory without a route of the aggregate.
            queryRoutes.map { it.handlerKey }.toSet().assert().isEqualTo(moduleKeys)
            // No snapshot or event route outside the query module except the known non-query commands.
            routes.map { it.handlerKey }
                .filter { key -> QUERY_NAMESPACES.any { key.startsWith(it) } }
                .filterNot { it in NON_QUERY_ROUTE_KEYS }
                .toSet().assert().isEqualTo(moduleKeys)
            // The router serves each query route with the query module's factory, not an override.
            val registrar = context.getBean(RouteHandlerFunctionRegistrar::class.java)
            module.httpFactories.forEach { factory ->
                registrar.getHttpFactory(factory.handlerKey).assert().isSameAs(factory)
            }

            val actual = JsonNodeFactory.instance.arrayNode()
            queryRoutes.sortedWith(compareBy({ it.routeId }, { it.method }, { it.path })).forEach { route ->
                actual.addObject()
                    .put("routeId", route.routeId)
                    .put("method", route.method)
                    .put("path", route.path)
                    .put("handlerKey", route.handlerKey)
            }
            actual.assert().isEqualTo(golden(actual))
        }
    }

    @Test
    fun `every gateway query route runs the backend call in the HTTP query context`() {
        withRouter { context, client, recorder ->
            val moduleKeys = context.getBean(QueryRouteModule::class.java).httpFactories.map { it.handlerKey }.toSet()
            val routes = orderRoutes(
                context
            ).filter { it.handlerKey in moduleKeys && it.handlerKey !in NON_GATEWAY_KEYS }
            routes.assert().isNotEmpty()
            routes.forEach { route ->
                recorder.clear()
                val status = client.send(route)
                status.assert().describedAs(route.routeId).isEqualTo(expectedStatus(route.handlerKey))
                val calls = recorder.filter { it.namedAggregate.isSameAggregateName(ORDER) }
                calls.assert().describedAs("${route.routeId} backend calls").hasSize(1)
                assertRanInHttpQueryContext(route, calls.single())
            }
        }
    }

    /** What every gateway query route must establish around its backend call. */
    private fun assertRanInHttpQueryContext(route: HttpRouteContract, call: BackendCall) {
        val tenant = route.requestTenant()
        call.filter.leaves().assert().describedAs("${route.routeId} backend filter").contains(tenant)
        call.context.queryScope().leaves().assert().describedAs("${route.routeId} query scope").contains(tenant)
    }

    /** A `{tenantId}` path variable takes precedence over the tenant header. */
    private fun HttpRouteContract.requestTenant(): TenantIdFilter =
        TenantIdFilter(if ("{$TENANT_ID_VARIABLE}" in path) PATH_TENANT else HEADER_TENANT)

    private fun WebTestClient.send(route: HttpRouteContract): HttpStatus {
        val spec = method(HttpMethod.valueOf(route.method)).uri(route.concretePath())
            .header(CommandComponent.Header.TENANT_ID, HEADER_TENANT)
            .accept(MediaType.APPLICATION_JSON)
        val body = BODIES[route.handlerKey]
        val exchange = if (body == null) {
            spec.exchange()
        } else {
            spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
        }
        val result = exchange.expectBody(String::class.java).returnResult()
        return HttpStatus.valueOf(result.status.value())
    }

    private fun HttpRouteContract.concretePath(): String = PATH_VARIABLE.replace(path) { match ->
        requireNotNull(
            PATH_VALUES[match.groupValues[1]]
        ) { "No test value for path variable ${match.value} of $routeId" }
    }

    private fun expectedStatus(handlerKey: String): HttpStatus =
        if (handlerKey in EMPTY_IS_NOT_FOUND_KEYS) HttpStatus.NOT_FOUND else HttpStatus.OK

    private fun orderRoutes(context: AssertableApplicationContext): List<HttpRouteContract> =
        context.getBean(RouterSpecs::class.java).toRouteCatalog().routes.filter { route ->
            val metadata = route.handlerMetadata as? HttpRouteHandlerMetadata.Aggregate ?: return@filter false
            metadata.aggregateRouteMetadata.aggregateMetadata.namedAggregate.isSameAggregateName(ORDER)
        }

    private fun golden(actual: JsonNode): JsonNode {
        if (System.getenv(UPDATE_ENV) == "true" || Files.notExists(GOLDEN)) {
            Files.createDirectories(GOLDEN.parent)
            Files.writeString(GOLDEN, JsonSerializer.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n")
        }
        return JsonSerializer.readTree(Files.readString(GOLDEN))
    }

    @Suppress("UNCHECKED_CAST")
    private fun withRouter(
        verify: (AssertableApplicationContext, WebTestClient, ConcurrentLinkedQueue<BackendCall>) -> Unit
    ) {
        val recorder = ConcurrentLinkedQueue<BackendCall>()
        val snapshotFactory = object : SnapshotQueryBackendFactory {
            override fun create(namedAggregate: NamedAggregate): QueryBackendBinding<SnapshotQueryBackend> =
                QueryBackendBinding(RecordingBackend(namedAggregate, recorder), SNAPSHOT_SCHEMA.asProvider())
        }
        val eventFactory = EventStreamQueryBackendFactory { namedAggregate ->
            QueryBackendBinding(RecordingBackend(namedAggregate, recorder), EVENT_STREAM_SCHEMA.asProvider())
        }
        ApplicationContextRunner()
            .enableWow()
            .withPropertyValues(
                "${BiScriptProperties.PREFIX}.enabled=true",
                "${BiScriptProperties.PREFIX}.consumer-group-namespace=test",
            )
            .withBean(SnapshotQueryBackendFactory::class.java, { snapshotFactory })
            .withBean(EventStreamQueryBackendFactory::class.java, { eventFactory })
            .withBean(CommandWaitNotifier::class.java, { mockk() })
            .withBean(CommandGateway::class.java, { SagaVerifier.defaultCommandGateway() })
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(SnapshotStore::class.java, { NoOpSnapshotStore })
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withBean(StateEventCompensator::class.java, { mockk() })
            .withBean(EventCompensateSupporter::class.java, { mockk() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
                EventSourcingAutoConfiguration::class.java,
                AggregateAutoConfiguration::class.java,
                OpenAPIAutoConfiguration::class.java,
                BiDeploymentInspectorAutoConfiguration::class.java,
                QueryAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
            )
            .run { context ->
                context.assert().hasNotFailed()
                val router = context.getBean("commandRouterFunction") as RouterFunction<ServerResponse>
                verify(context, WebTestClient.bindToRouterFunction(router).build(), recorder)
            }
    }

    /** One backend call, with the Reactor context the gateway ran it in. */
    private data class BackendCall(
        val namedAggregate: NamedAggregate,
        val operation: String,
        val filter: FilterExpression,
        val context: ContextView,
    )

    private class RecordingBackend(
        namedAggregate: NamedAggregate,
        private val recorder: ConcurrentLinkedQueue<BackendCall>,
        private val delegate: SnapshotQueryBackend = NoOpSnapshotQueryBackend(namedAggregate),
    ) : SnapshotQueryBackend by delegate, EventStreamQueryBackend {
        private fun <T : Any> record(operation: String, filter: FilterExpression, result: () -> Mono<T>): Mono<T> =
            Mono.deferContextual { context ->
                recorder += BackendCall(namedAggregate, operation, filter, context)
                result()
            }

        private fun <T : Any> recordMany(operation: String, filter: FilterExpression, result: () -> Flux<T>): Flux<T> =
            Flux.deferContextual { context ->
                recorder += BackendCall(namedAggregate, operation, filter, context)
                result()
            }

        override fun single(query: ISingleQuery, schema: QueryModelSchema): Mono<ObjectNode> =
            record("single", query.filter) { delegate.single(query, schema) }

        override fun list(query: IListQuery, schema: QueryModelSchema): Flux<ObjectNode> =
            recordMany("list", query.filter) { delegate.list(query, schema) }

        override fun paged(query: IPagedQuery, schema: QueryModelSchema): Mono<PagedList<ObjectNode>> =
            record("paged", query.filter) { delegate.paged(query, schema) }

        override fun cursor(query: ICursorQuery, schema: QueryModelSchema): Mono<CursorPage<ObjectNode>> =
            record("cursor", query.filter) { delegate.cursor(query, schema) }

        override fun count(query: FilterExpression, schema: QueryModelSchema): Mono<Long> =
            record("count", query) { delegate.count(query, schema) }

        override fun aggregate(query: AggregationQuery, schema: QueryModelSchema): Flux<ObjectNode> =
            recordMany("aggregate", query.filter) { delegate.aggregate(query, schema) }
    }

    private fun FilterExpression.leaves(): Set<FilterExpression> =
        if (this is AndFilter) operands.flatMap { it.leaves() }.toSet() else setOf(this)

    private companion object {
        const val UPDATE_ENV = "WOW_GOLDEN_UPDATE"
        val GOLDEN: Path = Path.of("src/test/resources/golden/query-route-inventory.json")

        val ORDER: NamedAggregate = Order::class.java.aggregateRouteMetadata().aggregateMetadata.namedAggregate
        const val TENANT_ID_VARIABLE = "tenantId"
        const val HEADER_TENANT = "header-tenant"
        const val PATH_TENANT = "path-tenant"

        val PATH_VARIABLE = Regex("\\{([^}]+)}")
        val PATH_VALUES = mapOf(
            TENANT_ID_VARIABLE to PATH_TENANT,
            "ownerId" to "request-owner",
            "spaceId" to "request-space",
            "id" to "order-1",
            "headVersion" to "1",
            "tailVersion" to "2",
        )

        val QUERY_NAMESPACES = listOf(
            BuiltInHttpRouteHandlerKeys.Snapshot.LOAD.substringBeforeLast('.') + ".",
            BuiltInHttpRouteHandlerKeys.Event.LOAD.substringBeforeLast('.') + ".",
        )

        /** Snapshot and event routes that are commands on the aggregate, served by other route modules. */
        val NON_QUERY_ROUTE_KEYS = setOf(
            BuiltInHttpRouteHandlerKeys.Snapshot.REGENERATE,
            BuiltInHttpRouteHandlerKeys.Snapshot.BATCH_REGENERATE,
            BuiltInHttpRouteHandlerKeys.Event.COMPENSATE,
            BuiltInHttpRouteHandlerKeys.Event.RESEND_STATE,
        )

        /**
         * Query module routes that do not reach a query gateway: they read or refresh the backend's query model
         * schema directly. They are excluded from the HTTP query context assertion and change in a later step of the
         * query refactor (capability descriptor).
         */
        val NON_GATEWAY_KEYS = setOf(
            BuiltInHttpRouteHandlerKeys.Snapshot.SCHEMA,
            BuiltInHttpRouteHandlerKeys.Snapshot.SCHEMA_REFRESH,
            BuiltInHttpRouteHandlerKeys.Event.SCHEMA,
            BuiltInHttpRouteHandlerKeys.Event.SCHEMA_REFRESH,
        )

        /** Single-result routes answer an empty backend result with 404. */
        val EMPTY_IS_NOT_FOUND_KEYS = setOf(
            BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE,
            BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE_STATE,
            BuiltInHttpRouteHandlerKeys.Snapshot.LOAD,
        )

        const val ALL = """{"op":"MATCH_ALL"}"""
        val BODIES = mapOf(
            BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE to """{"filter":$ALL}""",
            BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE_STATE to """{"filter":$ALL}""",
            BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY to """{"filter":$ALL,"limit":10}""",
            BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY_STATE to """{"filter":$ALL,"limit":10}""",
            BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY to """{"filter":$ALL,"pagination":{"index":1,"size":10}}""",
            BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY_STATE to
                """{"filter":$ALL,"pagination":{"index":1,"size":10}}""",
            BuiltInHttpRouteHandlerKeys.Snapshot.CURSOR_QUERY to """{"filter":$ALL,"size":10}""",
            BuiltInHttpRouteHandlerKeys.Snapshot.CURSOR_QUERY_STATE to """{"filter":$ALL,"size":10}""",
            BuiltInHttpRouteHandlerKeys.Snapshot.COUNT to ALL,
            BuiltInHttpRouteHandlerKeys.Snapshot.AGGREGATION to
                """{"filter":$ALL,"metrics":[{"type":"COUNT","alias":"count"}]}""",
            BuiltInHttpRouteHandlerKeys.Event.LIST_QUERY to """{"filter":$ALL,"limit":10}""",
            BuiltInHttpRouteHandlerKeys.Event.PAGED_QUERY to """{"filter":$ALL,"pagination":{"index":1,"size":10}}""",
            BuiltInHttpRouteHandlerKeys.Event.CURSOR_QUERY to """{"filter":$ALL,"size":10}""",
            BuiltInHttpRouteHandlerKeys.Event.COUNT to ALL,
            BuiltInHttpRouteHandlerKeys.Event.AGGREGATION to
                """{"filter":$ALL,"metrics":[{"type":"COUNT","alias":"count"}]}""",
        )

        val SNAPSHOT_SCHEMA = schema(QueryModel.SNAPSHOT)
        val EVENT_STREAM_SCHEMA = schema(QueryModel.EVENT_STREAM)

        /** The system fields request scopes and load routes filter on, each with the capabilities they need. */
        private fun schema(model: QueryModel): QueryModelSchema {
            val identity = setOf(
                QueryCapability.EXACT_MATCH,
                QueryCapability.PRESENCE,
                QueryCapability.SORT,
                QueryCapability.CURSOR_SORT,
            )
            val fields = mapOf(
                "id" to (QueryValueType.STRING to identity),
                "aggregateId" to (QueryValueType.STRING to identity),
                "tenantId" to (QueryValueType.STRING to identity),
                "ownerId" to (QueryValueType.STRING to identity),
                "spaceId" to (QueryValueType.STRING to identity),
                "deleted" to (QueryValueType.BOOLEAN to setOf(QueryCapability.EXACT_MATCH, QueryCapability.PRESENCE)),
                "version" to (QueryValueType.INTEGER to identity + QueryCapability.RANGE),
            )
            val definition = LogicalQuerySchema(
                QueryValueSchema(
                    QueryValueKind.OBJECT,
                    properties = fields.mapValues { (_, value) ->
                        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(value.first))
                    },
                ),
            )
            return QueryModelSchema(
                model,
                emptySet(),
                definition,
                definition.values.filterKeys { it.segments.isNotEmpty() }.mapValues { (path, _) ->
                    val name = (path.segments.single() as QueryPathSegment.Property).name
                    val capabilities = fields.getValue(name).second
                    QueryValueBindings(capabilities.associateWith { QueryFieldBindingTemplate(path, null) }, path, path)
                },
            )
        }

        private fun QueryModelSchema.asProvider(): QueryModelSchemaProvider = object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.just(this@asProvider)
            override fun refresh(): Mono<QueryModelSchema> = schema()
        }
    }
}
