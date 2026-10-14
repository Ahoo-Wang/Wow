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

package me.ahoo.wow.http

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.snapshot.SnapshotRebuilds
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.command.CommandHandler
import me.ahoo.wow.http.command.CommandTarget
import me.ahoo.wow.http.command.Negotiation
import me.ahoo.wow.http.command.RequestHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.query.QueryHandler
import me.ahoo.wow.http.query.QueryTarget
import me.ahoo.wow.http.read.StateHandler
import me.ahoo.wow.http.read.StateReads
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.QueryOwnership
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.spi.StorageUnavailableException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.CoRouterFunctionDsl
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

/**
 * The routes of the public port (roadmap 5b.2; decisions D16, D30, D92–D104, D112, D113), materialized from
 * [manifest] alone:
 *
 * - every route of every command (`CommandManifest.routes`, decision D104) sends that command through [gateway], and so
 *   does `POST /{context}/{aggregate}/{command}` for each command that [gateway] transforms (decision D182) and the
 *   Manifest gives no route without an id: its transformer finds the aggregate. The Manifest the node serves lists
 *   that route too;
 * - the job commands of each context whose Manifest has jobs (decision D198): `POST /{context}/schedule/{id}/wow.schedule.<kebab>`;
 * - the snapshot rebuild of each context whose Manifest has an aggregate that keeps snapshots (decision D172):
 *   `POST /{context}/snapshot/{aggregate}/wow.snapshot.rebuild`;
 * - the lifecycle commands each aggregate lists (decision D113): `DELETE /{context}/{aggregate}/{id}` sends
 *   `DeleteAggregate`, `POST /{context}/{aggregate}/{id}/wow.lifecycle.<kebab>` the others;
 * - `GET /{context}/{aggregate}/{id}/state`, `?version=`, `?at=` and `GET …/{id}/state/trace` read [reads]
 *   (decisions D40, D112), in the tenant the gateway's [tenants][CommandGateway.tenants] decide, as for a command
 *   (decision D116);
 * - `POST /{context}/{aggregate}/{model}/query` and its `GET` (decisions D127, D131) query the models of
 *   [AggregateManifest.queries] that [queries] serves, the snapshots and the events of each aggregate type;
 * - `POST /wow/dataset/{context}/{dataset}/query` and its `GET` query the datasets of [queries] (decision D145) as a
 *   snapshot model is queried, and `GET /wow/dataset` lists those the caller sees; a dataset that joins or leaves
 *   while the node runs is served or not from then on;
 * - `GET /wow/manifest` answers [manifest], unless [WowHttpOptions.manifestEndpoint] is off;
 * - `GET /wow/id/global` answers a new global id of the gateway's [generator][CommandGateway.ids] (decision D171) as
 *   `text/plain`;
 * - any other request under a context of the Manifest is a 404 problem.
 *
 * Every route is authenticated by [callerResolver] (401 `wow.auth.unauthenticated` when it returns null). Errors are
 * RFC 9457 problems whose statuses come from the Manifest's error tables ([ErrorStatuses]). The host serves other
 * routes under a context's path only by composing them before these.
 */
fun wowRoutes(
    manifest: Manifest,
    gateway: CommandGateway,
    reads: StateReads = StateReads.NONE,
    callerResolver: CallerResolver = CallerResolver.ANONYMOUS,
    options: WowHttpOptions = WowHttpOptions(),
    queries: HttpQueries = HttpQueries.NONE,
): RouterFunction<ServerResponse> {
    val statuses = ErrorStatuses(manifest, options.errorStatuses)
    val authenticator = Authenticator(callerResolver, statuses)
    val headers = RequestHeaders(statuses)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val commands = CommandHandler(
        gateway = gateway,
        authenticator = authenticator,
        headers = headers,
        statuses = statuses,
        options = options,
        scope = scope,
    )
    val queryHandler = QueryHandler(queries, authenticator, headers, statuses, gateway.tenants, scope)
    val ownerships = QueryOwnership.of(manifest)
    val states = StateHandler(reads, authenticator, headers, statuses, gateway.tenants, ownerships::ownershipOf)
    val served = withTransformedRoutes(manifest, gateway)
    val manifestBody = HttpJson.write(ManifestTree.write(served))
    return coRouter {
        if (options.manifestEndpoint) {
            GET(WowPaths.MANIFEST) { request ->
                statuses.answering {
                    authenticator.authenticate(request)
                    Negotiation.checkAcceptable(request, alsoEventStream = false)
                    ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(manifestBody)
                }
            }
        }
        GET(WowPaths.GLOBAL_ID) { request ->
            statuses.answering {
                authenticator.authenticate(request)
                ServerResponse.ok().contentType(MediaType.TEXT_PLAIN).bodyValueAndAwait(gateway.ids.global.next())
            }
        }
        if (queries.datasets !== DatasetQueryGateways.NONE) {
            datasetRoutes(queryHandler)
        }
        served.contexts.forEach { context ->
            context.aggregates.forEach { aggregate ->
                commandRoutes(context.name, aggregate, commands, statuses)
                val type = AggregateType(context.name, aggregate.name)
                val state = "/${context.name}/${aggregate.name}/${CommandRoutes.ID}/${WowPaths.STATE}"
                GET(state) { request -> statuses.answering { states.handle(request, type) } }
                GET("$state/${WowPaths.TRACE}") { request -> statuses.answering { states.trace(request, type) } }
                queryRoutes(type, aggregate, queries, queryHandler)
            }
            if (context.subscriptions.any { it.job != null }) {
                jobRoutes(context.name, commands, statuses)
            }
            if (context.aggregates.any { it.snapshot.enabled }) {
                rebuildRoute(context.name, commands, statuses)
            }
        }
        manifest.contexts.map { it.name }.distinct().forEach { context ->
            path("/$context/**").invoke { request ->
                Problem.ofStatus(HttpStatus.NOT_FOUND, "No route ${request.method()} ${request.path()}.").respond()
            }
        }
    }
}

/**
 * [manifest] with the route without an id (`POST /{context}/{aggregate}/{command}`) of each command [gateway]
 * transforms (decision D182) that has none, such as a command whose transformer finds its aggregate by a reserved key.
 */
internal fun withTransformedRoutes(manifest: Manifest, gateway: CommandGateway): Manifest = manifest.copy(
    contexts = manifest.contexts.map { context ->
        context.copy(
            aggregates = context.aggregates.map { aggregate ->
                val type = AggregateType(context.name, aggregate.name)
                aggregate.copy(
                    commands = aggregate.commands.map { command ->
                        val route = CommandRoutes.unaddressed(context.name, aggregate.name, command.name)
                        if (route !in command.routes && gateway.transforms(type, command.name)) {
                            command.copy(routes = command.routes + route)
                        } else {
                            command
                        }
                    },
                )
            },
        )
    },
)

/** The routes of [aggregate]'s commands and of the lifecycle commands it lists. */
private fun CoRouterFunctionDsl.commandRoutes(
    context: String,
    aggregate: AggregateManifest,
    handler: CommandHandler,
    statuses: ErrorStatuses,
) {
    aggregate.commands.forEach { command ->
        command.routes.forEach { route ->
            val target = CommandTarget(context, aggregate.name, command.name, route.path.contains(CommandRoutes.ID))
            (method(HttpMethod.valueOf(route.method)) and path(route.path)).invoke { request ->
                statuses.answering { handler.handle(request, target) }
            }
        }
    }
    val base = "/$context/${aggregate.name}/${CommandRoutes.ID}"
    aggregate.lifecycle.forEach { name ->
        val target = CommandTarget(context, aggregate.name, name, byId = true)
        if (name == Lifecycle.DELETE_AGGREGATE) {
            DELETE(base) { request -> statuses.answering { handler.handle(request, target) } }
        } else {
            POST("$base/${CommandRoutes.segment(name)}") { request ->
                statuses.answering { handler.handle(request, target) }
            }
        }
    }
}

/**
 * The routes of the job commands of [context]'s built-in job aggregate (decision D198), when its Manifest has jobs:
 * `POST /{context}/schedule/{id}/wow.schedule.enable`, `.../wow.schedule.reschedule` and `.../wow.schedule.disable`,
 * the instance's id being the job's name. They are sent through the gateway like any command, so its authorizer
 * (CoSec's, by the action of the route) decides who may enable, reschedule or disable a job in their tenant.
 */
private fun CoRouterFunctionDsl.jobRoutes(context: String, handler: CommandHandler, statuses: ErrorStatuses) {
    listOf(ScheduleJobs.ENABLE, ScheduleJobs.RESCHEDULE, ScheduleJobs.DISABLE).forEach { name ->
        val target = CommandTarget(context, ScheduleJobs.AGGREGATE, name, byId = true)
        POST(CommandRoutes.byIdPath(context, ScheduleJobs.AGGREGATE, name)) { request ->
            statuses.answering { handler.handle(request, target) }
        }
    }
}

/**
 * The route of the snapshot rebuild command of [context]'s built-in rebuild aggregate (decision D172), when its Manifest
 * has an aggregate that keeps snapshots: `POST /{context}/snapshot/{aggregate}/wow.snapshot.rebuild` with the body
 * `{"ids": [...]}`, the instance's id being the name of the aggregate type whose snapshots to rebuild. It is sent
 * through the gateway like any command, so its authorizer (CoSec's, by the action of the route) decides who may
 * rebuild snapshots in their tenant.
 */
private fun CoRouterFunctionDsl.rebuildRoute(context: String, handler: CommandHandler, statuses: ErrorStatuses) {
    val target = CommandTarget(context, SnapshotRebuilds.AGGREGATE, SnapshotRebuilds.REBUILD, byId = true)
    POST(CommandRoutes.byIdPath(context, SnapshotRebuilds.AGGREGATE, SnapshotRebuilds.REBUILD)) { request ->
        statuses.answering { handler.handle(request, target) }
    }
}

/**
 * The query routes of [aggregate]'s query models ([AggregateManifest.queries], decision D127) that [queries] serves:
 * `POST` runs a query, `GET` answers the descriptor.
 */
private fun CoRouterFunctionDsl.queryRoutes(
    type: AggregateType,
    aggregate: AggregateManifest,
    queries: HttpQueries,
    handler: QueryHandler,
) {
    aggregate.queries.forEach { query ->
        val served = when (query.model) {
            QueryRoutes.SNAPSHOT -> queries.snapshots(type) != null
            QueryRoutes.EVENTS -> queries.events(type) != null
            else -> false
        }
        if (!served) return@forEach
        val target = QueryTarget(type, query.model)
        query.routes.forEach { route ->
            when (route.method) {
                QueryRoutes.GET -> GET(route.path) { request -> handler.describe(request, target) }
                else -> POST(route.path) { request -> handler.query(request, target) }
            }
        }
    }
}

/** The dataset routes (decision D145): the list, and each dataset's query and descriptor by its path variables. */
private fun CoRouterFunctionDsl.datasetRoutes(handler: QueryHandler) {
    GET(WowPaths.DATASETS) { request -> handler.listDatasets(request) }
    val path = "${WowPaths.DATASETS}/{$DATASET_CONTEXT}/{$DATASET_NAME}/${QueryRoutes.SEGMENT}"
    POST(path) { request -> datasetOf(request)?.let { handler.queryDataset(request, it) } ?: noDataset(request) }
    GET(path) { request -> datasetOf(request)?.let { handler.describeDataset(request, it) } ?: noDataset(request) }
}

private const val DATASET_CONTEXT = "context"
private const val DATASET_NAME = "dataset"

/** The dataset name of [request]'s path, or null when its segments cannot name one. */
private fun datasetOf(request: ServerRequest): AggregateType? = try {
    AggregateType(request.pathVariable(DATASET_CONTEXT), request.pathVariable(DATASET_NAME))
} catch (_: IllegalArgumentException) {
    null
}

private suspend fun noDataset(request: ServerRequest): ServerResponse =
    Problem.ofStatus(HttpStatus.NOT_FOUND, "No dataset at ${request.path()}.").respond()

/**
 * [block]'s response, or the problem it was answered with: a store that is unavailable where no result carries it
 * (a state read, say) is `wow.storage.unavailable`, as a command's result would be.
 */
private suspend inline fun ErrorStatuses.answering(block: () -> ServerResponse): ServerResponse = try {
    block()
} catch (problem: ProblemException) {
    problem.problem.respond()
} catch (unavailable: StorageUnavailableException) {
    logger.warn(unavailable) { "A request failed: a store is unavailable." }
    problemOf(FrameworkErrors.STORAGE_UNAVAILABLE, unavailable.message!!).respond()
}

private val logger = KotlinLogging.logger {}
