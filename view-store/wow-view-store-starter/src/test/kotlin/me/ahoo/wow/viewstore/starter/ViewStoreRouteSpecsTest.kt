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
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.viewstore.domain.preferences.ViewPreferences
import me.ahoo.wow.viewstore.domain.view.View
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod

/**
 * The routes Wow generates for the view store's aggregates in a host of another context, and the ones the starter
 * adds beside them.
 */
class ViewStoreRouteSpecsTest {
    private val hostContext = MaterializedNamedBoundedContext("example-service")
    private val routerSpecs = RouterSpecs(hostContext).build()
    private val paths = ViewStorePaths(hostContext)
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

    @Test
    fun `OpenAPI shows the custom and the scoped routes`() {
        val openApi = OpenAPI()
        routerSpecs.mergeOpenAPIFromCatalog(openApi)
        ViewStoreOpenApi(paths).withoutClosedRoutes(openApi, guard.closedContracts)
        ViewStoreOpenApi(paths).merge(openApi)
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
        ViewStoreOpenApi(paths).merge(openApi)
        openApi.tags.count { it.name == ViewStoreOpenApi.TAG }.assert().isEqualTo(1)
    }
}
