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
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.query.QueryAutoConfiguration
import me.ahoo.wow.spring.boot.starter.query.testQuerySchema
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.query.DefaultQueryRequestScope
import me.ahoo.wow.webflux.route.query.HttpQueryGuard
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentLinkedQueue

class QueryPolicyWebFluxTest {
    @Test
    fun `registered policy constrains HTTP queries after preparation for both models`() {
        val policy = QueryPolicy { identity, _ -> Mono.just(OwnerIdFilter(identity.get(PRINCIPAL))) }
        withClient(policy) { client, backend ->
            ROUTES.forEach { route ->
                listOf("alice", "bob").forEach { principal ->
                    backend.received.clear()
                    client.post().uri(route.path)
                        .header(PRINCIPAL, principal)
                        .header(CommandComponent.Header.TENANT_ID, "trusted-tenant")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .bodyValue(route.body).exchange()
                        .expectStatus().isOk
                        .expectBody().json(route.response)

                    val (model, filter) = backend.received.single()
                    model.assert().isEqualTo(route.model)
                    val expected = setOf(TenantIdFilter("trusted-tenant"), OwnerIdFilter(principal)) +
                        if (model == QueryModel.SNAPSHOT) setOf(DeletionFilter(DeletionState.ACTIVE)) else emptySet()
                    filter.leaves().assert().isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun `registered policy rejection returns forbidden without invoking either backend`() {
        val policy = QueryPolicy { _, _ -> Mono.error(ResponseStatusException(HttpStatus.FORBIDDEN, "Policy denied")) }
        withClient(policy) { client, backend ->
            ROUTES.forEach { route ->
                client.post().uri(route.path)
                    .header(PRINCIPAL, "alice")
                    .header(CommandComponent.Header.TENANT_ID, "trusted-tenant")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .bodyValue(route.body).exchange()
                    .expectStatus().isForbidden
                    .expectBody().jsonPath("$.errorCode").isEqualTo("Forbidden")
            }
            backend.received.assert().isEmpty()
        }
    }

    private fun withClient(policy: QueryPolicy, verify: (WebTestClient, RecordingBackend) -> Unit) {
        val metadata = Order::class.java.aggregateRouteMetadata()
        val backend = RecordingBackend(metadata.aggregateMetadata.namedAggregate)
        val snapshotFactory = object : SnapshotQueryBackendFactory {
            override fun create(namedAggregate: NamedAggregate): QueryBackendBinding<SnapshotQueryBackend> =
                QueryBackendBinding(backend, schemaProvider(QueryModel.SNAPSHOT))
        }
        val eventFactory = EventStreamQueryBackendFactory {
            QueryBackendBinding(backend, schemaProvider(QueryModel.EVENT_STREAM))
        }
        ApplicationContextRunner().enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .withBean(SnapshotQueryBackendFactory::class.java, { snapshotFactory })
            .withBean(EventStreamQueryBackendFactory::class.java, { eventFactory })
            .withBean(QueryPolicy::class.java, { policy })
            .withBean(QueryFilter::class.java, {
                object : QueryFilter {
                    override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> =
                        Mono.just(context.query.withFilter(MatchAllFilter))
                            .contextWrite { it.put(PRINCIPAL, "intruder") }
                }
            })
            .run { context ->
                context.assert().hasNotFailed()
                val module = WebFluxAutoConfiguration().queryRouteModule(
                    context,
                    snapshotFactory,
                    eventFactory,
                    DefaultQueryRequestScope,
                    WebFluxRequestExceptionHandler(),
                    HttpQueryGuard(),
                )
                val router = RouterFunctions.route()
                ROUTES.forEach { route ->
                    val contract = HttpRouteContract(
                        routeId = route.handlerKey,
                        method = Https.Method.POST,
                        path = route.path,
                        handlerKey = route.handlerKey,
                        handlerMetadata = HttpRouteHandlerMetadata.Aggregate(metadata),
                    )
                    router.POST(
                        route.path,
                        module.httpFactories.single { it.handlerKey == route.handlerKey }.create(contract)
                    )
                }
                val client = WebTestClient.bindToRouterFunction(router.build())
                    .webFilter<WebTestClient.RouterFunctionSpec>(
                        WebFilter { exchange, chain ->
                            // The test authentication boundary supplies a different identity for each request.
                            chain.filter(exchange).contextWrite {
                                it.put(PRINCIPAL, requireNotNull(exchange.request.headers.getFirst(PRINCIPAL)))
                            }
                        }
                    )
                    .build()
                verify(client, backend)
            }
    }

    private fun schemaProvider(model: QueryModel) = object : QueryModelSchemaProvider {
        private val schema = testQuerySchema(model)
        override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)
        override fun refresh(): Mono<QueryModelSchema> = schema()
    }

    private class RecordingBackend(namedAggregate: NamedAggregate) :
        SnapshotQueryBackend by NoOpSnapshotQueryBackend(namedAggregate), EventStreamQueryBackend {
        val received = ConcurrentLinkedQueue<Pair<QueryModel, FilterExpression>>()

        override fun list(query: IListQuery, schema: QueryModelSchema): Flux<ObjectNode> {
            received += schema.model to query.filter
            return Flux.empty()
        }

        override fun count(query: FilterExpression, schema: QueryModelSchema): Mono<Long> {
            received += schema.model to query
            return Mono.just(0L)
        }

        override fun aggregate(query: AggregationQuery, schema: QueryModelSchema): Flux<ObjectNode> {
            received += schema.model to query.filter
            return Flux.empty()
        }
    }

    private fun FilterExpression.leaves(): Set<FilterExpression> =
        if (this is AndFilter) operands.flatMap { it.leaves() }.toSet() else setOf(this)

    private data class Route(val model: QueryModel, val handlerKey: String, val body: String, val response: String) {
        val path = "/$handlerKey"
    }

    private companion object {
        const val PRINCIPAL = "X-Test-Principal"
        const val INPUT_FILTER = """{"op":"TENANT_ID","value":"user-input"}"""
        const val QUERY_BODY = """{"filter":$INPUT_FILTER,"limit":10}"""
        const val AGGREGATION_BODY = """{"filter":$INPUT_FILTER,"metrics":[{"type":"COUNT","alias":"count"}]}"""
        val ROUTES = listOf(
            Route(QueryModel.SNAPSHOT, BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY, QUERY_BODY, "[]"),
            Route(QueryModel.SNAPSHOT, BuiltInHttpRouteHandlerKeys.Snapshot.COUNT, INPUT_FILTER, "0"),
            Route(QueryModel.SNAPSHOT, BuiltInHttpRouteHandlerKeys.Snapshot.AGGREGATION, AGGREGATION_BODY, "[]"),
            Route(QueryModel.EVENT_STREAM, BuiltInHttpRouteHandlerKeys.Event.LIST_QUERY, QUERY_BODY, "[]"),
            Route(QueryModel.EVENT_STREAM, BuiltInHttpRouteHandlerKeys.Event.COUNT, INPUT_FILTER, "0"),
            Route(QueryModel.EVENT_STREAM, BuiltInHttpRouteHandlerKeys.Event.AGGREGATION, AGGREGATION_BODY, "[]"),
        )
    }
}
