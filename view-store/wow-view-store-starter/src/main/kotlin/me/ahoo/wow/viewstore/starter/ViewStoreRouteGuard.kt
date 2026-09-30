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

import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.infra.TypeNameMapper.toType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.BuiltInHttpRoutePaths
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.ViewStoreService.VIEW_AGGREGATE_NAME
import me.ahoo.wow.viewstore.api.view.CreateView
import me.ahoo.wow.viewstore.api.view.RenameView
import me.ahoo.wow.viewstore.api.view.SaveView
import me.ahoo.wow.viewstore.api.view.ShareView
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern

/**
 * Wow routes every aggregate with its built-in state, snapshot, event and maintenance routes, and has no switch to
 * leave them out for one aggregate. Most of them read or rewrite a view without the application check, some without
 * the owner, so for the view store's aggregates only these stay open:
 * - five of the command routes Wow generates (create, save, rename, share, delete), and its snapshot queries under
 *   `tenant/{tenantId}/owner/{ownerId}`;
 * - the routes the starter adds: claim, system views, preferences and replay.
 *
 * Every other route Wow generates for them is closed, and so is the command facade (`POST /wow/command/send`) for
 * their commands: it takes the aggregate id, the owner and the headers from the caller and passes none of the
 * view store's own routes.
 */
class ViewStoreRouteGuard(
    paths: ViewStorePaths,
    routerSpecs: RouterSpecs,
    private val namedAggregates: Set<NamedAggregate>,
) {
    private data class Route(val method: HttpMethod, val pattern: PathPattern) {
        fun matches(method: HttpMethod, path: PathContainer): Boolean = this.method == method && pattern.matches(path)
    }

    private val viewStoreContracts: List<HttpRouteContract> = routerSpecs.toRouteCatalog().routes.filter { contract ->
        contract.namedAggregate()?.let { named -> namedAggregates.any { it.isSameAggregateName(named) } } == true
    }

    private val scopedPrefix = "${paths.scope}/"

    /**
     * The routes of the view store's aggregates Wow generates and the starter keeps open: the command routes, and the
     * snapshot queries under the tenant and the owner (the query policy then keeps them in the request's application).
     */
    val openContracts: List<HttpRouteContract> = viewStoreContracts.filter {
        (it.commandType() in OPEN_VIEW_COMMANDS && it.namedAggregate()?.aggregateName == VIEW_AGGREGATE_NAME) ||
            (it.handlerKey in SNAPSHOT_QUERY_KEYS && it.path.startsWith(scopedPrefix))
    }

    /** Every other route Wow generates for the view store's aggregates. */
    val closedContracts: List<HttpRouteContract> = viewStoreContracts - openContracts.toSet()

    /**
     * The open routes, matched **case-sensitively**. On a case-sensitive host (Spring's default) a path whose case
     * differs from an open route's literals is not routed to it, but it may be routed to a closed route through a
     * variable: `…/view/REQUESTS/state` is Wow's `view/{id}/state` with the id `REQUESTS`, not the replay route
     * `view/requests/{requestId}`. So an open route wins over a closed one only on an exact match. On a
     * case-insensitive host that path refuses a little more than Spring routes to a closed route (404 instead of the
     * replay of a request id `state`), never less.
     */
    private val openRoutes: List<Route> = (
        openContracts.map { HttpMethod.valueOf(it.method) to it.path } +
            listOf(
                HttpMethod.GET to paths.systemViews,
                HttpMethod.GET to paths.systemView,
                HttpMethod.GET to paths.preferences,
                HttpMethod.PUT to paths.preferences,
                HttpMethod.GET to paths.replay,
                HttpMethod.PUT to paths.claim,
            )
        ).map { (method, path) -> Route(method, path.toExactPattern()) }

    /** The closed routes, matched case-insensitively: at least what a host of either case mode routes to them. */
    private val closedRoutes: List<Route> = closedContracts.map {
        Route(HttpMethod.valueOf(it.method), it.path.toPattern())
    }
    private val commandFacade: PathPattern = BuiltInHttpRoutePaths.Global.COMMAND_SEND.toPattern()

    /**
     * Whether [path] is a closed route of the view store's aggregates. An open route (the starter's own routes are
     * routed before Wow's, and Wow routes its open routes before the closed ones they overlap) wins only when it
     * matches exactly, case included; every other path a closed route matches in any case is closed.
     */
    fun isClosed(method: HttpMethod, path: PathContainer): Boolean {
        if (openRoutes.any { it.matches(method, path) }) {
            return false
        }
        return closedRoutes.any { it.matches(method, path) }
    }

    fun isClosed(method: HttpMethod, path: String): Boolean = isClosed(method, PathContainer.parsePath(path))

    /**
     * Whether a command facade request sends a command to the view store, found as Wow finds it (the
     * `Command-Aggregate-Context` and `Command-Aggregate-Name` headers, else the aggregate of the `Command-Type`), or
     * by any of those naming the view store on its own.
     */
    fun isViewStoreFacadeCommand(method: HttpMethod, path: PathContainer, headers: HttpHeaders): Boolean {
        if (method != HttpMethod.POST || !commandFacade.matches(path)) {
            return false
        }
        val context = headers.getFirst(CommandComponent.Header.COMMAND_AGGREGATE_CONTEXT)?.trim()
        if (!context.isNullOrEmpty() && context.equals(ViewStoreService.SERVICE_NAME, ignoreCase = true)) {
            return true
        }
        val aggregateName = headers.getFirst(CommandComponent.Header.COMMAND_AGGREGATE_NAME)?.trim()
        if (!context.isNullOrEmpty() && !aggregateName.isNullOrEmpty()) {
            val aggregateType = runCatching {
                MaterializedNamedAggregate(context, aggregateName).requiredAggregateType<Any>()
            }.getOrNull()
            if (aggregateType != null && aggregateType.isViewStoreAggregate()) {
                return true
            }
        }
        val commandType = headers.getFirst(CommandComponent.Header.COMMAND_TYPE)?.trim()
        if (commandType.isNullOrEmpty()) {
            return false
        }
        if (commandType.startsWith(VIEW_STORE_PACKAGE)) {
            return true
        }
        val commandAggregate = runCatching { commandType.toType<Any>().requiredNamedAggregate() }.getOrNull()
        return commandAggregate != null && commandAggregate.isViewStore()
    }

    private fun Class<*>.isViewStoreAggregate(): Boolean =
        runCatching { requiredNamedAggregate().isViewStore() }.getOrDefault(false)

    private fun NamedAggregate.isViewStore(): Boolean =
        contextName == ViewStoreService.SERVICE_NAME || namedAggregates.any { it.isSameAggregateName(this) }

    private fun HttpRouteContract.commandType(): Class<*>? =
        (handlerMetadata as? HttpRouteHandlerMetadata.Command)?.commandRouteMetadata?.commandMetadata?.commandType

    private fun HttpRouteContract.namedAggregate(): NamedAggregate? = when (val metadata = handlerMetadata) {
        is HttpRouteHandlerMetadata.Aggregate -> metadata.aggregateRouteMetadata.aggregateMetadata.namedAggregate
        is HttpRouteHandlerMetadata.Command -> metadata.aggregateRouteMetadata.aggregateMetadata.namedAggregate
        HttpRouteHandlerMetadata.None -> null
    }

    private companion object {
        /**
         * The commands of `view` with an open route. Wow's recover and resource-tags commands (routed by default for
         * every aggregate), and every command of `view_preferences` (whose one route is the starter's), are not among
         * them, so they are in-process only.
         */
        val OPEN_VIEW_COMMANDS: Set<Class<*>> = setOf(
            CreateView::class.java,
            SaveView::class.java,
            RenameView::class.java,
            ShareView::class.java,
            DefaultDeleteAggregate::class.java,
        )

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

        /** The package of the view store's commands, so one the host cannot resolve is refused all the same. */
        const val VIEW_STORE_PACKAGE = "me.ahoo.wow.viewstore."
    }
}
