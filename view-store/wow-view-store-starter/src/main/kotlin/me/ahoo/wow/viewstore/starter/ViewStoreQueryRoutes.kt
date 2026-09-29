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

package me.ahoo.wow.viewstore.starter

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.catalog.RouteCatalog
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.render.OpenApiRenderer
import me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrar
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * Wow generates an aggregate's snapshot query routes within a tenant or within an owner, never both. The view store
 * is scoped by both, so this serves each of its owner-scoped snapshot query routes again under
 * `…/tenant/{tenantId}/owner/{ownerId}/…`, with Wow's own handler: the path's tenant and owner become the query
 * scope as on any Wow route.
 */
class ViewStoreQueryRoutes(
    paths: ViewStorePaths,
    routerSpecs: RouterSpecs,
    namedAggregates: Set<NamedAggregate>,
) {
    companion object {
        /** Wow's snapshot query routes: the only query routes of the view store that are open. */
        val SNAPSHOT_QUERY_KEYS = setOf(
            BuiltInHttpRouteHandlerKeys.Snapshot.AGGREGATION,
            BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
            BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
            BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY_STATE,
            BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY,
            BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY_STATE,
            BuiltInHttpRouteHandlerKeys.Snapshot.CURSOR_QUERY,
            BuiltInHttpRouteHandlerKeys.Snapshot.CURSOR_QUERY_STATE,
            BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE,
            BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE_STATE,
        )
        private val TENANT_PARAMETER = HttpParameter(
            name = ViewStorePaths.TENANT_ID,
            location = HttpParameterLocation.PATH,
            required = true,
            componentRef = "wow.${ViewStorePaths.TENANT_ID}",
        )
        private const val ROUTE_ID_SUFFIX = ".tenant_owner"
    }

    private val ownerPrefix = "${paths.prefix}/owner/{${ViewStorePaths.OWNER_ID}}/"
    private val scopedPrefix = "${paths.scope}/"

    /** The tenant-and-owner copies of the view store's owner-scoped snapshot query routes. */
    val contracts: List<HttpRouteContract> = routerSpecs.toRouteCatalog().routes
        .filter { contract ->
            contract.handlerKey in SNAPSHOT_QUERY_KEYS &&
                contract.path.startsWith(ownerPrefix) &&
                contract.namedAggregate()?.let { named -> namedAggregates.any { it.isSameAggregateName(named) } } == true
        }
        .map { contract ->
            contract.copy(
                routeId = contract.routeId + ROUTE_ID_SUFFIX,
                path = scopedPrefix + contract.path.removePrefix(ownerPrefix),
                parameters = listOf(TENANT_PARAMETER) + contract.parameters,
            )
        }

    fun routerFunction(registrar: RouteHandlerFunctionRegistrar): RouterFunction<ServerResponse>? {
        if (contracts.isEmpty()) {
            return null
        }
        val builder = RouterFunctions.route()
        contracts.forEach { contract ->
            val factory = requireNotNull(registrar.getHttpFactory(contract.handlerKey)) {
                "No handler for [${contract.handlerKey}] of route [${contract.path}]."
            }
            val predicate = RequestPredicates.path(contract.path)
                .and(RequestPredicates.method(HttpMethod.valueOf(contract.method)))
                .and(RequestPredicates.accept(*MediaType.parseMediaTypes(contract.accept).toTypedArray()))
            builder.route(predicate, factory.create(contract))
        }
        return builder.build()
    }

    /** Renders the routes as Wow renders its own, with Wow's component context, and adds their paths. */
    fun merge(openApi: OpenAPI, routerSpecs: RouterSpecs) {
        val rendered = OpenApiRenderer(routerSpecs.componentContext).render(RouteCatalog(contracts), OpenAPI())
        rendered.paths?.forEach { (path, item) -> openApi.path(path, item) }
    }

    private fun HttpRouteContract.namedAggregate(): NamedAggregate? = when (val metadata = handlerMetadata) {
        is HttpRouteHandlerMetadata.Aggregate -> metadata.aggregateRouteMetadata.aggregateMetadata.namedAggregate
        is HttpRouteHandlerMetadata.Command -> metadata.aggregateRouteMetadata.aggregateMetadata.namedAggregate
        HttpRouteHandlerMetadata.None -> null
    }
}
