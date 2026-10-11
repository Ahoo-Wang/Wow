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

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys.Dataset
import me.ahoo.wow.openapi.contributor.global.DatasetQueryRouteContributor
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.BackendPage
import me.ahoo.wow.query.CursorPosition
import me.ahoo.wow.query.CursorPositionCodec
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.PageWindow
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.dataset.DatasetQueryBackend
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.schema.QueryDatasets
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.serialization.toObjectNode
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.dataset.DatasetQueryHandlerFunctionFactory
import me.ahoo.wow.webflux.route.dataset.DatasetQueryOperation
import me.ahoo.wow.webflux.route.dataset.DefaultDatasetQueryRequestScope
import me.ahoo.wow.webflux.route.query.HttpQueryGuard
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

class DatasetQueryAutoConfigurationTest {
    @Test
    fun `no dataset is configured by default`() {
        ApplicationContextRunner().enableWow()
            .withUserConfiguration(QuerySchemaAutoConfiguration::class.java, QueryAutoConfiguration::class.java)
            .run { context ->
                context.assert().hasNotFailed()
                context.getBean(QueryDatasets::class.java).all.assert().isEmpty()
                context.getBean(DatasetQueryGateways::class.java).all.assert().isEmpty()
            }
    }

    @Test
    fun `a configured dataset needs a dataset backend`() {
        runner().run { context ->
            context.assert().hasFailed()
            context.startupFailure.assert().rootCause().hasMessageContaining("no dataset query backend")
        }
    }

    @Test
    fun `a dataset name is its context and its name after the last dot`() {
        datasetName("analytics.orders").assert().isEqualTo(MaterializedNamedAggregate("analytics", "orders"))
        datasetName("bi.sales.orders").assert().isEqualTo(MaterializedNamedAggregate("bi.sales", "orders"))
        listOf("orders", ".orders", "analytics.").forEach { assertThrows<IllegalArgumentException> { datasetName(it) } }
    }

    @Test
    fun `dataset routes query each configured dataset under the caller's tenant`() {
        val backend = RecordingBackend()
        runner()
            .withBean(
                DatasetQueryBackendFactory::class.java,
                { DatasetQueryBackendFactory { QueryBackendBinding(backend, ADAPTER) } }
            )
            .withUserConfiguration(DatasetQueryRouteAutoConfiguration.RouteContractConfiguration::class.java)
            .run { context ->
                context.assert().hasNotFailed()
                context.getBeansOfType(
                    RouteContributor::class.java
                ).values.assert().contains(DatasetQueryRouteContributor)
                val client = client(context.getBean(DatasetQueryGateways::class.java))
                client.post().uri("/tenant/t1/dataset/analytics/orders/list")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"filter":{"field":"city","op":"EQ","value":"Hangzhou"}}""").exchange()
                    .expectStatus().isOk
                    .expectBody().jsonPath("$[0].phone").isEqualTo("*******1234")
                val admitted = backend.admitted.single()
                val tenant = admitted.filterLeaves().single { it is TenantIdFilter }
                (tenant as TenantIdFilter).value.assert().isEqualTo("t1")
                admitted.systemPath(tenant).assert().isEqualTo("tenant_id")

                backend.admitted.clear()
                client.post().uri("/dataset/analytics/orders/count").header(CommandHeaders.TENANT_ID, "t2")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"op":"MATCH_ALL"}""").exchange()
                    .expectStatus().isOk.expectBody().json("1")
                (backend.admitted.single().filterLeaves().single { it is TenantIdFilter } as TenantIdFilter)
                    .value.assert().isEqualTo("t2")

                client.post().uri("/dataset/analytics/orders/paged").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"filter":{"op":"MATCH_ALL"}}""").exchange()
                    .expectStatus().isOk.expectBody().jsonPath("$.total").isEqualTo(1)
                client.post().uri("/dataset/analytics/orders/cursor").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"filter":{"op":"MATCH_ALL"},"size":1}""").exchange()
                    .expectStatus().isOk.expectBody().jsonPath("$.list[0].order_id").isEqualTo("o1")
                client.post().uri("/dataset/analytics/orders/aggregation").contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"metrics":[{"type":"COUNT","alias":"orders"}]}""").exchange()
                    .expectStatus().isOk.expectBody().jsonPath("$[0].orders").isEqualTo(1)
                client.get().uri("/dataset/analytics/orders/schema").exchange()
                    .expectStatus().isOk
                    .expectHeader().exists("ETag")
                    .expectBody().jsonPath("$.model").isEqualTo("DATASET").jsonPath("$.record.identity")
                    .isEqualTo("order_id")
                client.post().uri("/dataset/analytics/orders/single").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"filter":{"op":"ID","value":"o1"}}""").exchange()
                    .expectStatus().isOk.expectBody().jsonPath("$.order_id").isEqualTo("o1")
                client.get().uri("/dataset/analytics/missing/schema").exchange().expectStatus().isNotFound
            }
    }

    private fun runner(): ApplicationContextRunner = ApplicationContextRunner().enableWow()
        .withPropertyValues("wow.query.datasets=analytics.orders")
        .withUserConfiguration(QuerySchemaAutoConfiguration::class.java, QueryAutoConfiguration::class.java)

    private fun client(gateways: DatasetQueryGateways): WebTestClient {
        val router = RouterFunctions.route()
        DatasetQueryRouteContributor.contributeGlobal(
            MaterializedNamedAggregate("analytics", "orders")
        ).forEach { contract ->
            val factory = DatasetQueryHandlerFunctionFactory(
                contract.handlerKey,
                OPERATIONS.getValue(contract.handlerKey),
                gateways,
                DefaultDatasetQueryRequestScope.INSTANCE,
                WebFluxRequestExceptionHandler(),
                HttpQueryGuard(),
            )
            router.route(
                org.springframework.web.reactive.function.server.RequestPredicates.method(
                    org.springframework.http.HttpMethod.valueOf(contract.method),
                ).and(org.springframework.web.reactive.function.server.RequestPredicates.path(contract.path)),
                factory.create(contract),
            )
        }
        return WebTestClient.bindToRouterFunction(router.build()).build()
    }

    private fun AdmittedQuery<*>.filterLeaves(): List<FilterExpression> {
        val filter = when (val query = query) {
            is Queryable<*> -> query.filter
            is AggregationQuery -> query.filter
            is FilterExpression -> query
            else -> error("Unexpected query [$query].")
        }
        fun leaves(node: FilterExpression): List<FilterExpression> =
            if (node is me.ahoo.wow.api.query.AndFilter) node.operands.flatMap(::leaves) else listOf(node)
        return leaves(filter)
    }

    private class RecordingBackend : DatasetQueryBackend {
        val admitted = java.util.concurrent.CopyOnWriteArrayList<AdmittedQuery<*>>()
        override val namedAggregate: NamedAggregate = MaterializedNamedAggregate("analytics", "orders")
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON

        override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> {
            admitted += query
            return Flux.just(row())
        }

        override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> {
            admitted += query
            return Mono.just(BackendPage(listOf(row()), total = 1, positions = listOf(CursorPosition(listOf("o1")))))
        }

        override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> {
            admitted += query
            return Mono.just(1)
        }

        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> {
            admitted += query
            return Flux.just("""{"orders":1}""".toObjectNode())
        }

        private fun row(): ObjectNode =
            """{"order_id":"o1","tenant_id":"t1","city":"Hangzhou","phone":"13800001234"}""".toObjectNode()
    }

    private companion object {
        val OPERATIONS = mapOf(
            Dataset.SCHEMA to DatasetQueryOperation.SCHEMA,
            Dataset.COUNT to DatasetQueryOperation.COUNT,
            Dataset.AGGREGATION to DatasetQueryOperation.AGGREGATION,
            Dataset.LIST_QUERY to DatasetQueryOperation.LIST,
            Dataset.PAGED_QUERY to DatasetQueryOperation.PAGED,
            Dataset.CURSOR_QUERY to DatasetQueryOperation.CURSOR,
            Dataset.SINGLE to DatasetQueryOperation.SINGLE,
        )

        val CAPABILITIES = setOf(
            QueryCapability.PRESENCE,
            QueryCapability.EXACT_MATCH,
            QueryCapability.LITERAL_MATCH,
            QueryCapability.RANGE,
            QueryCapability.SORT,
            QueryCapability.CURSOR_SORT,
            QueryCapability.AGGREGATE_TERMS,
        )

        val ADAPTER = QueryStorageAdapter { logical ->
            Mono.just(
                QueryStorageFacts(
                    bindings = logical.values.keys.filter { it.segments.size == 1 }.associateWith { path ->
                        val native = QueryFieldBindingTemplate(path, null)
                        QueryValueBindings(CAPABILITIES.associateWith { native }, path, path)
                    },
                ),
            )
        }
    }
}
