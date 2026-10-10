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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.getContextAliasPrefix
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.viewstore.domain.preferences.ViewPreferences
import me.ahoo.wow.viewstore.domain.view.View
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser

/**
 * The routes Wow generates for the view store's aggregates in a host of another context, and the ones the starter
 * adds beside them.
 */
class ViewStoreRouteSpecsTest {
    private val hostContext = MaterializedNamedBoundedContext("example-service")
    private val routerSpecs = RouterSpecs(hostContext).build()
    private val paths = ViewStorePaths(hostContext)
    private val hostPrefix = hostContext.getContextAliasPrefix()
    private val scope = "/view-store/tenant/{tenantId}/owner/{ownerId}"
    private val namedAggregates = setOf(
        View::class.java.aggregateRouteMetadata().aggregateMetadata.namedAggregate,
        ViewPreferences::class.java.aggregateRouteMetadata().aggregateMetadata.namedAggregate,
    )

    @Test
    fun `Wow routes the view commands under the tenant and the owner`() {
        val routes = routerSpecs.toRouteCatalog().routes.map { "${it.method} ${it.path}" }
        routes.assert().contains(
            "POST $scope/view",
            "PUT $scope/view/{id}/save",
            "PUT $scope/view/{id}/rename",
            "PUT $scope/view/{id}/share",
            "DELETE $scope/view/{id}",
        )
        routes.assert().contains("POST /owner/{ownerId}/cart/add_cart_item")
    }

    private val guard = ViewStoreRouteGuard(paths, routerSpecs, namedAggregates)
    private val snapshotQueries = listOf(
        "aggregation", "count", "cursor", "cursor/state", "list", "list/state", "paged", "paged/state", "single",
        "single/state",
    )

    /** The open routes, whoever serves them: Wow's that the guard keeps open, and the starter's own. */
    private val exposed = guard.openContracts.map { "${it.method} ${it.path}" } +
        listOf(
            "GET ${paths.systemViews}",
            "GET ${paths.systemView}",
            "GET ${paths.preferences}",
            "PUT ${paths.preferences}",
            "GET ${paths.replay}",
            "PUT ${paths.claim}",
        )

    private fun String.concrete(): String = replace(Regex("\\{[^}]+}"), "x")

    /**
     * Every route of the view store's aggregates that is open: a route Wow adds for them in a later version is
     * closed until it is listed here.
     */
    @Test
    fun `the open routes of the view store are exactly these`() {
        exposed.assert().containsExactlyInAnyOrder(
            *(
                listOf(
                    "POST $scope/view",
                    "PUT $scope/view/{id}/save",
                    "PUT $scope/view/{id}/rename",
                    "PUT $scope/view/{id}/share",
                    "PUT $scope/view/{id}/claim",
                    "DELETE $scope/view/{id}",
                    "GET $scope/system-views",
                    "GET $scope/system-views/{id}",
                    "GET $scope/definitions/{definitionId}/preferences",
                    "PUT $scope/definitions/{definitionId}/preferences",
                    "GET $scope/view/requests/{requestId}",
                ) +
                    snapshotQueries.map { "POST $scope/view/snapshot/$it" } +
                    snapshotQueries.map { "POST $scope/view_preferences/snapshot/$it" }
                ).toTypedArray()
        )
        exposed.forEach { route ->
            val (method, path) = route.split(" ", limit = 2)
            guard.isClosed(HttpMethod.valueOf(method), path.concrete()).assert().isFalse()
        }
    }

    /**
     * Everything else Wow generates for them is closed: state and tracing reads, snapshot and event loads, snapshot
     * regeneration, state resend, compensation, schemas, the tenant-only or owner-only queries, every event-stream
     * query (under the tenant and the owner too), and Wow's default recover and resource-tags commands.
     */
    @Test
    fun `every other route Wow generates for the view store is closed`() {
        guard.closedContracts.map { it.handlerKey }.toSet().assert().contains(
            BuiltInHttpRouteHandlerKeys.State.LOAD_AGGREGATE,
            BuiltInHttpRouteHandlerKeys.State.LOAD_VERSIONED_AGGREGATE,
            BuiltInHttpRouteHandlerKeys.State.LOAD_TIME_BASED_AGGREGATE,
            BuiltInHttpRouteHandlerKeys.State.AGGREGATE_TRACING,
            BuiltInHttpRouteHandlerKeys.Snapshot.LOAD,
            BuiltInHttpRouteHandlerKeys.Snapshot.REGENERATE,
            BuiltInHttpRouteHandlerKeys.Snapshot.BATCH_REGENERATE,
            BuiltInHttpRouteHandlerKeys.Event.LOAD,
            BuiltInHttpRouteHandlerKeys.Event.COMPENSATE,
            BuiltInHttpRouteHandlerKeys.Event.RESEND_STATE,
            BuiltInHttpRouteHandlerKeys.Event.LIST_QUERY,
            BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
            BuiltInHttpRouteHandlerKeys.Command.COMMAND,
        )
        val closedCommands = guard.closedContracts
            .filter { it.handlerKey == BuiltInHttpRouteHandlerKeys.Command.COMMAND }
            .map { "${it.method} ${it.path}" }
        // Wow's recover and resource tags, for both aggregates, and preferences' delete: in-process only.
        closedCommands.assert().hasSize(5)
        closedCommands.assert().contains("DELETE $scope/view_preferences/{id}")
        guard.closedContracts.map { "${it.method} ${it.path}" }.assert()
            .contains("POST $scope/view/event/list", "POST $scope/view_preferences/event/count")
        guard.closedContracts.forEach { contract ->
            guard.isClosed(HttpMethod.valueOf(contract.method), contract.path.concrete()).assert().isTrue()
        }
    }

    /** A route as the test models Spring's dispatch: the method, the path template, and whether the guard closes it. */
    private data class Dispatch(val method: String, val path: String, val closed: Boolean)

    private val closedKeys = guard.closedContracts.map { "${it.method} ${it.path}" }.toSet()

    /** Spring's order: the starter's router function (`@Order(0)`) first, then Wow's routes in dispatch order. */
    private val dispatchOrder: List<Dispatch> = listOf(
        "GET" to paths.systemViews,
        "GET" to paths.systemView,
        "GET" to paths.preferences,
        "PUT" to paths.preferences,
        "GET" to paths.replay,
        "PUT" to paths.share,
        "PUT" to paths.claim,
    ).map { (method, path) -> Dispatch(method, path, closed = false) } +
        routerSpecs.toRouteCatalog().dispatchRoutes.map {
            Dispatch(it.method, it.path, closed = "${it.method} ${it.path}" in closedKeys)
        }

    private fun parser(caseSensitive: Boolean) = PathPatternParser().apply { isCaseSensitive = caseSensitive }

    /** The route Spring dispatches [path] to on a host whose path matching is [caseSensitive] or not. */
    private fun springRoute(method: String, path: String, caseSensitive: Boolean): Dispatch? {
        val container = PathContainer.parsePath(path)
        val parser = parser(caseSensitive)
        return dispatchOrder.firstOrNull { it.method == method && parser.parse(it.path).matches(container) }
    }

    private fun String.isVariable() = startsWith("{")

    /** The witness segment for an open segment [x] and a closed segment [y], literals upper-cased as asked. */
    private fun segment(x: String, y: String, upperOpen: Boolean, upperClosed: Boolean): String {
        val literal = if (x.isVariable()) y else x
        val upper = when {
            x.isVariable() && y.isVariable() -> return "x"
            !x.isVariable() && !y.isVariable() -> upperOpen || upperClosed
            !x.isVariable() -> upperOpen
            else -> upperClosed
        }
        return if (upper) literal.uppercase() else literal
    }

    /**
     * Concrete paths both templates match (ignoring case): each segment the literal of either template, or `x`
     * where both have a variable, in the templates' case and with the literals of either template upper-cased.
     */
    private fun witnesses(open: String, closed: String): List<String> {
        val a = open.split("/")
        val b = closed.split("/")
        if (a.size != b.size) return emptyList()
        val aligned = a.zip(b)
        if (aligned.any { (x, y) -> !x.isVariable() && !y.isVariable() && !x.equals(y, ignoreCase = true) }) {
            return emptyList()
        }
        fun build(upperOpen: Boolean, upperClosed: Boolean) = aligned.joinToString("/") { (x, y) ->
            segment(x, y, upperOpen, upperClosed)
        }
        return listOf(build(false, false), build(true, false), build(false, true), build(true, true)).distinct()
    }

    /**
     * No open and closed route can disagree: for every pair of an open and a closed route of the same method, on
     * every path both match in some case, and on a host of either case mode, the guard closes the path whenever
     * Spring dispatches it to the closed route, and keeps open a path in the routes' own case that Spring dispatches
     * to the open route. (Round 6: `GET …/view/REQUESTS/state` is Wow's closed `view/{id}/state` on a case-sensitive
     * host, although it matches the replay route `view/requests/{requestId}` ignoring case.)
     */
    @Test
    fun `no open and closed route pair disagrees in either case mode`() {
        val open = dispatchOrder.filterNot { it.closed }
        val closed = dispatchOrder.filter { it.closed }
        var overlaps = 0
        open.forEach { o ->
            closed.filter { it.method == o.method }.forEach { c ->
                witnesses(o.path, c.path).forEach { path ->
                    overlaps++
                    listOf(true, false).forEach { caseSensitive ->
                        val route = springRoute(o.method, path, caseSensitive)
                        val isClosed = guard.isClosed(HttpMethod.valueOf(o.method), path)
                        val description = "${o.method} $path (case-sensitive=$caseSensitive) -> $route"
                        if (route?.closed == true) {
                            isClosed.assert().describedAs(description).isTrue()
                        }
                        if (route != null && !route.closed && path == path.lowercase()) {
                            isClosed.assert().describedAs(description).isFalse()
                        }
                    }
                }
            }
        }
        overlaps.assert().isGreaterThan(0)
        // The reported case: dispatched to the closed state route on a case-sensitive host.
        val requests = "/view-store/tenant/t1/owner/alice/view/REQUESTS/state"
        springRoute("GET", requests, caseSensitive = true)!!.closed.assert().isTrue()
        guard.isClosed(HttpMethod.GET, requests).assert().isTrue()
        guard.isClosed(HttpMethod.GET, "/view-store/tenant/t1/owner/alice/view/requests/state").assert().isFalse()
    }

    @Test
    fun `OpenAPI shows the custom and the scoped routes`() {
        val openApi = OpenAPI()
        routerSpecs.mergeOpenAPI(openApi)
        ViewStoreClosedRoutesFilter(guard.closedContracts).filter(openApi)
        ViewStoreOpenApi(paths, hostPrefix).merge(openApi)
        openApi.paths.keys.assert().doesNotContain(
            "$scope/view/{id}/state",
            "/view-store/tenant/{tenantId}/view/{id}/state/tracing",
            "/view-store/owner/{ownerId}/view/snapshot/list",
            "$scope/view/event/list",
        )
        openApi.paths["$scope/view/{id}"]!!.readOperations().map { it.operationId }.assert().hasSize(1)
        // The commands carry no id: Wow takes it from the `{id}` path parameter.
        listOf("save", "rename", "share").forEach { action ->
            val operation = openApi.paths["$scope/view/{id}/$action"]!!.put
            operation.parameters.map { it.name ?: it.`$ref` }.any { it.endsWith("id") }.assert().isTrue()
        }
        openApi.paths["$scope/view/{id}"]!!.delete.parameters.map { it.name ?: it.`$ref` }
            .any { it.endsWith("id") }.assert().isTrue()
        openApi.paths.keys.assert().contains(
            "$scope/view/snapshot/list",
            "$scope/system-views",
            "$scope/system-views/{id}",
            "$scope/definitions/{definitionId}/preferences",
            "$scope/view/requests/{requestId}",
        )
        val preferences = openApi.paths["$scope/definitions/{definitionId}/preferences"]!!
        preferences.get.operationId.assert().isEqualTo("view-store.getPreferences")
        preferences.put.parameters.map { it.name }.assert().contains("Command-Request-Id", "Command-Aggregate-Version")
        openApi.paths["$scope/view/requests/{requestId}"]!!.get.responses.keys.assert().contains("200", "204")
        openApi.components.schemas.keys.any { it.endsWith("SystemView") }.assert().isTrue()
        openApi.tags.count { it.name == ViewStoreOpenApi.TAG }.assert().isEqualTo(1)
        ViewStoreOpenApi(paths, hostPrefix).merge(openApi)
        openApi.tags.count { it.name == ViewStoreOpenApi.TAG }.assert().isEqualTo(1)
    }

    /**
     * The starter names the types it adds the way the host's document does, so a type the host already has (a map,
     * a JSON node) is not added again at the root under a bare name; a client generated from the host stays as it was.
     */
    @Test
    fun `OpenAPI adds no unprefixed schema at the root`() {
        val openApi = OpenAPI()
        routerSpecs.mergeOpenAPI(openApi)
        val before = openApi.components.schemas.keys.toSet()
        ViewStoreOpenApi(paths, hostPrefix).merge(openApi)
        val added = openApi.components.schemas.keys - before
        added.assert().isNotEmpty()
        added.filterNot { it.contains('.') }.assert().isEmpty()
        openApi.components.schemas.keys.assert().doesNotContain(
            "ObjectNode",
            "StringObjectMap",
            "StringStringListMap",
            "StringStringMap",
        )
    }
}
