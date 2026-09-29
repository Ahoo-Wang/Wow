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

package me.ahoo.wow.webflux.route

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.catalog.RouteCategory
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.contributor.aggregate.command.CommandRouteContributor
import me.ahoo.wow.openapi.contributor.aggregate.event.EventRouteContributor
import me.ahoo.wow.openapi.contributor.aggregate.snapshot.SnapshotRouteContributor
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * An owned aggregate without a static tenant publishes its commands under `/tenant/{tenantId}/owner/{ownerId}`; one
 * whose command path ends in `count` generates `POST …/{resource}/{id}/count`, which overlaps the tenant + owner
 * snapshot and event-stream count queries `POST …/{resource}/snapshot/count` and `POST …/{resource}/event/count`.
 * The catalog lists commands first, so a router following that order hands both queries to the command (with `id`
 * = `snapshot` / `event`); the router follows the catalog's dispatch order, where the literal segment wins, so each
 * request reaches its own handler.
 */
class OverlappingRouteDispatchTest {
    private val routerSpecs = RouterSpecs(
        currentContext = MOCK_AGGREGATE_METADATA,
        routeContributors = listOf(CountingAggregateRouteContributor)
    ).build()

    private fun client(routerFunction: RouterFunction<ServerResponse>): WebTestClient =
        WebTestClient.bindToRouterFunction(routerFunction).build()

    private val registrar = RouteHandlerFunctionRegistrar(
        routerSpecs.toRouteCatalog().routes
            .map { it.handlerKey }
            .distinct()
            .map(::EchoHandlerFunctionFactory)
    )

    @Test
    fun `the count command overlaps both count queries and is listed before them`() {
        val paths = routerSpecs.toRouteCatalog().routes.map { it.path }
        val command = paths.indexOf("$TENANT_OWNER/counting_aggregate/{id}/count")
        val snapshotCount = paths.indexOf("$TENANT_OWNER/counting_aggregate/snapshot/count")
        val eventCount = paths.indexOf("$TENANT_OWNER/counting_aggregate/event/count")

        (command in 0 until snapshotCount).assert().isTrue()
        (command in 0 until eventCount).assert().isTrue()
    }

    @Test
    fun `a router in catalog order sends the count queries to the command`() {
        val builder = RouterFunctions.route()
        val materializer = HttpRouteMaterializer(registrar)
        routerSpecs.toRouteCatalog().routes.forEach {
            val binding = materializer.materialize(it)
            builder.route(binding.predicate, binding.handlerFunction)
        }
        val client = client(builder.build())

        client.post("/tenant/tenant-a/owner/owner-a/counting_aggregate/snapshot/count")
            .assert().isEqualTo("command id=snapshot")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "/tenant/tenant-a", "/owner/owner-a", "/tenant/tenant-a/owner/owner-a"])
    fun `each count query reaches its own handler`(scope: String) {
        val client = client(RouterFunctionBuilder(routerSpecs, registrar).build())

        client.post("$scope/counting_aggregate/snapshot/count")
            .assert().isEqualTo(BuiltInHttpRouteHandlerKeys.Snapshot.COUNT)
        client.post("$scope/counting_aggregate/event/count")
            .assert().isEqualTo(BuiltInHttpRouteHandlerKeys.Event.COUNT)
    }

    @Test
    fun `the count command still reaches its own handler`() {
        val client = client(RouterFunctionBuilder(routerSpecs, registrar).build())

        client.post("/tenant/tenant-a/owner/owner-a/counting_aggregate/aggregate-1/count")
            .assert().isEqualTo("command id=aggregate-1")
    }

    private fun WebTestClient.post(path: String): String? {
        return post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
    }

    private companion object {
        const val TENANT_OWNER = "/tenant/{tenantId}/owner/{ownerId}"
    }
}

@CommandRoute(action = "count", method = CommandRoute.Method.POST, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class CountItems(@AggregateId val id: String)

/**
 * The TCK mock aggregate with [CountItems] mounted, published as the owned resource `counting_aggregate`.
 */
private val COUNTING_AGGREGATE_ROUTE_METADATA = AggregateRouteMetadata(
    enabled = true,
    aggregateMetadata = MOCK_AGGREGATE_METADATA.copy(
        command = MOCK_AGGREGATE_METADATA.command.copy(mountedCommands = setOf(CountItems::class.java))
    ),
    resourceName = "counting_aggregate",
    spaced = false,
    owner = AggregateRoute.Owner.ALWAYS
)

private object CountingAggregateRouteContributor : RouteContributor {
    override val id: String = "test.counting-aggregate"
    override val category: RouteCategory = RouteCategory.GLOBAL
    override val order: Int = 0

    override fun contributeGlobal(
        currentContext: NamedBoundedContext,
        componentContext: OpenAPIComponentContext
    ): List<HttpRouteContract> {
        return listOf(CommandRouteContributor, SnapshotRouteContributor, EventRouteContributor).flatMap {
            it.contributeAggregate(currentContext, COUNTING_AGGREGATE_ROUTE_METADATA, componentContext)
        }
    }
}

private class EchoHandlerFunctionFactory(override val handlerKey: String) : HttpRouteHandlerFunctionFactory {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        val body = when (metadata) {
            is HttpRouteHandlerMetadata.Command -> null
            else -> handlerKey
        }
        return HandlerFunction { request ->
            ServerResponse.ok().bodyValue(body ?: "command id=${request.pathVariable("id")}")
        }
    }
}
