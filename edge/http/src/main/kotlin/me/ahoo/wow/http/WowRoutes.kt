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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.command.CommandHandler
import me.ahoo.wow.http.command.CommandTarget
import me.ahoo.wow.http.command.Negotiation
import me.ahoo.wow.http.command.RequestHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.http.problem.tooLargeProblem
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.query.QueryHandler
import me.ahoo.wow.http.query.QueryTarget
import me.ahoo.wow.http.read.StateHandler
import me.ahoo.wow.http.route.ServiceRouteHandling
import me.ahoo.wow.http.route.ServiceRoutes
import me.ahoo.wow.manifest.AggregateIdSource
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.manifest.withoutClassNames
import me.ahoo.wow.openapi.ManifestOpenApi
import me.ahoo.wow.openapi.OpenApiOptions
import me.ahoo.wow.openapi.OpenApiSecurityScheme
import me.ahoo.wow.query.admission.QueryTargets
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.policy.QueryOwnership
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.state.StateReads
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.PathContainer
import org.springframework.web.reactive.function.server.CoRouterFunctionDsl
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import reactor.core.publisher.Mono

/**
 * The routes of the public port (.2; –), materialized from
 * [manifest] alone:
 *
 * - every route of every command (`CommandManifest.routes`) sends that command through [gateway]: a command a
 *   transformer finds the aggregate of (`@Unaddressed`, or mounted) has its route without an id there, as the
 *   compiler writes it. The system commands are among them, routed as the Manifest says ([systemTarget]): the
 *   lifecycle commands an aggregate lists, its snapshot rebuild, and the job commands of its context;
 * - `GET /{context}/{aggregate}/{id}/state`, `?version=`, `?at=` and `GET …/{id}/state/trace` read [reads], and
 *   `GET /{context}/{aggregate}/state` and `…/state/trace` the caller's own aggregate of one whose id is its owner's,
 * in the tenant the gateway's [tenants][CommandGateway.tenants] decide, as for a command;
 * each state and each event of a trace masked as a snapshot query masks it, by the snapshot query
 *   gateway of [queries] or, without one, by [masks];
 * - `POST /{context}/{aggregate}/{model}/query` and its `GET` query the models of
 *   [AggregateManifest.queries] that [queries] serves, the snapshots and the events of each aggregate type;
 * - `POST /wow/dataset/{context}/{dataset}/query` and its `GET` query the datasets of [queries] as a
 *   snapshot model is queried, and `GET /wow/dataset` lists those the caller sees; a dataset that joins or leaves
 *   while the node runs is served or not from then on;
 * - `GET /wow/manifest` answers [manifest] without the JVM class names of its schemas ([withoutClassNames]), which
 *   the internal port's keeps, unless [WowHttpOptions.manifestEndpoint] is off;
 * - `GET /wow/openapi` answers the OpenAPI 3.1 document of these routes ([openApiOf],.2), unless
 *   [WowHttpOptions.openApiEndpoint] is off;
 * - any other request under a context of the Manifest is a 404 problem.
 *
 * Every route is authenticated by [callerResolver] (401 `wow.auth.unauthenticated` when it returns null). A command
 * request that carries `Wow-Call` is refused (403 `wow.auth.forbidden`) whatever its credential: the services'
 * synchronous calls are taken on the internal port alone ([callRoutes]). Errors are
 * RFC 9457 problems whose statuses come from the Manifest's error tables ([ErrorStatuses]). A context's service
 * routes ([ServiceRoutes]) are mounted from the Manifest too, each with the handler its module binds:
 * a route without one, or a handler of no route, fails here, so the node does not start. The host serves other
 * routes under a context's path only by composing them before these.
 *
 * The streamed answers (`text/event-stream`) are produced in [scope], apart from their requests: the host passes one
 * it cancels as the node stops, so no producer outlives the node.
 */
@InternalWowApi
fun wowRoutes(
    manifest: Manifest,
    gateway: CommandGateway,
    reads: StateReads = StateReads.NONE,
    callerResolver: CallerResolver = CallerResolver.ANONYMOUS,
    options: WowHttpOptions = WowHttpOptions(),
    queries: HttpQueries = HttpQueries.NONE,
    masks: PayloadMasks = PayloadMasks(manifest),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    routes: ServiceRoutes = ServiceRoutes.NONE,
): RouterFunction<ServerResponse> {
    val statuses = ErrorStatuses(manifest, options.errorStatuses)
    val boundRoutes = routes.bind(manifest)
    val authenticator = Authenticator(callerResolver, statuses)
    // The public port: its commands carry the default request facts.
    val headers = RequestHeaders(statuses, publicPort = true, clientFacts = options.clientFacts)
    val commands = CommandHandler(
        gateway = gateway,
        authenticator = authenticator,
        headers = headers,
        statuses = statuses,
        options = options,
        scope = scope,
    )
    // The read side's scope rules are:query's, over the tenants commands act in.
    val targets = QueryTargets(gateway.tenants, queries.crossTenant)
    val queryHandler = QueryHandler(queries, authenticator, headers, statuses, targets, scope)
    val ownerships = QueryOwnership.of(manifest)
    val states =
        StateHandler(reads, authenticator, headers, statuses, targets, ownerships::ownershipOf, queries, masks)
    val serviceRoutes = ServiceRouteHandling(routes, authenticator, headers, statuses, targets)
    return coRouter {
        if (options.manifestEndpoint) {
            val document = HttpJson.write(ManifestTree.write(manifest.withoutClassNames()))
            document(WowPaths.MANIFEST, document, statuses, authenticator)
        }
        if (options.openApiEndpoint) {
            document(WowPaths.OPENAPI, HttpJson.write(openApiOf(manifest, options, queries)), statuses, authenticator)
        }
        if (queries.datasets !== DatasetQueryGateways.NONE) {
            datasetRoutes(queryHandler)
        }
        aggregateRoutes(manifest) { context, aggregate ->
            commandRoutes(context, aggregate, commands, statuses)
            val type = AggregateType(context, aggregate.name)
            val state = "/$context/${aggregate.name}/${CommandRoutes.ID}/${WowPaths.STATE}"
            GET(state) { request -> statuses.answering { states.handle(request, type) } }
            GET("$state/${WowPaths.TRACE}") { request -> statuses.answering { states.trace(request, type) } }
            if (aggregate.idSource == AggregateIdSource.OWNER) {
                // The caller's own aggregate, whose id is its owner's: the same reads by no id.
                val own = "/$context/${aggregate.name}/${WowPaths.STATE}"
                GET(own) { request -> statuses.answering { states.handle(request, type) } }
                GET("$own/${WowPaths.TRACE}") { request -> statuses.answering { states.trace(request, type) } }
            }
            queryRoutes(type, aggregate, queries, queryHandler)
        }
        manifest.contexts.forEach { context -> contextRoutes(context, commands, statuses) }
        manifest.contexts.forEach { context ->
            context.routes.forEach { route ->
                val handler = boundRoutes.getValue(context.name to route.name)
                (method(HttpMethod.valueOf(route.route.method)) and path(route.route.path)).invoke { request ->
                    statuses.answering { serviceRoutes.handle(request, context, route, handler) }
                }
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
 * The OpenAPI document of the routes [wowRoutes] serves from [manifest], [options] and [queries], the one
 * `GET /wow/openapi` answers, with the [securitySchemes] the host's caller resolver accepts: the
 * document the host merges into springdoc's. The public port's routes alone: the internal port's call routes, cluster
 * channel and signals are never in it.
 */
@InternalWowApi
fun wowOpenApi(
    manifest: Manifest,
    options: WowHttpOptions = WowHttpOptions(),
    queries: HttpQueries = HttpQueries.NONE,
    securitySchemes: Map<String, OpenApiSecurityScheme> = emptyMap(),
): Map<String, Any?> = openApiOf(manifest, options, queries, securitySchemes)

/**
 * The OpenAPI document of the routes of [served] as [options] and
 * [queries] serve them: the query models [queries] serves, the datasets when it has them, the Manifest endpoint and the
 * error statuses the host configures; the [securitySchemes] when it has them.
 */
internal fun openApiOf(
    served: Manifest,
    options: WowHttpOptions,
    queries: HttpQueries,
    securitySchemes: Map<String, OpenApiSecurityScheme> = emptyMap(),
): Map<String, Any?> {
    val documented = served.copyWith(
        contexts =
        served.contexts.map { context ->
            context.copyWith(
                aggregates =
                context.aggregates.map { aggregate ->
                    val type = AggregateType(context.name, aggregate.name)
                    aggregate.copyWith(queries = aggregate.queries.filter { queries.serves(type, it.model) })
                },
            )
        },
    )
    return ManifestOpenApi.write(
        documented,
        OpenApiOptions(
            datasets = queries.datasets !== DatasetQueryGateways.NONE,
            manifestEndpoint = options.manifestEndpoint,
            openApiEndpoint = options.openApiEndpoint,
            errorStatuses = options.errorStatuses,
        ).withSecuritySchemes(securitySchemes),
    )
}

/** Whether [this] serves the query [model] of [type]: the snapshots and the events of the types it has gateways for. */
private fun HttpQueries.serves(type: AggregateType, model: String): Boolean = gateway(type, model) != null

/** `GET [path]`: [body], as JSON, to an authenticated caller that accepts it. */
private fun CoRouterFunctionDsl.document(
    path: String,
    body: ByteArray,
    statuses: ErrorStatuses,
    authenticator: Authenticator,
) {
    GET(path) { request ->
        statuses.answering {
            authenticator.authenticate(request)
            Negotiation.checkAcceptable(request, alsoEventStream = false)
            ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(body)
        }
    }
}

/**
 * The routes of the services' synchronous calls, served on the internal port beside
 * the cluster channel and the wait signals: every command route of [manifest], the same paths and answers as on the
 * public port, sending the command through [gateway]. Each request must come from a
 * [service][me.ahoo.wow.api.caller.Caller.service] identity, which [callerResolver] resolves (the service the request
 * names, [me.ahoo.wow.http.caller.DeclaredServices]), and carry `Wow-Call`; any other, one that names no
 * service too, is answered 403
 * `wow.auth.forbidden`. The public port
 * refuses `Wow-Call` ([wowRoutes]), so the root command a call asserts reaches a node only through its internal
 * network, from a service it trusts. A service calls only the commands [permits] lets it:
 * the domain commands by default, a system command (`wow.*`: the lifecycle, the snapshot rebuild, the schedules and the
 * jobs, whose routes are the public port's too) only when listed; any other is answered 403
 * [CallPermits.COMMAND_FORBIDDEN]. A call of a command route the Manifest does not have, in a context it serves, is
 * answered 400 `wow.command.unhandled` with its REJECTED result, not 404: the caller's contract names a
 * command this service does not decide, which a retry does not mend. The streamed answers are produced in [scope], as
 * [wowRoutes]'s are.
 */
@InternalWowApi
fun callRoutes(
    manifest: Manifest,
    gateway: CommandGateway,
    callerResolver: CallerResolver,
    options: WowHttpOptions = WowHttpOptions(),
    permits: CallPermits = CallPermits(emptyMap()),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): RouterFunction<ServerResponse> {
    val statuses = ErrorStatuses(manifest, options.errorStatuses)
    val commands = CommandHandler(
        gateway = gateway,
        // A request that names no service is no service identity either: 403, as for any other caller.
        authenticator = Authenticator(
            CallerResolver { request -> callerResolver.resolve(request) ?: Caller.ANONYMOUS },
            statuses,
        ),
        headers = RequestHeaders(statuses),
        statuses = statuses,
        options = options,
        scope = scope,
        internal = true,
        permits = permits,
    )
    return coRouter {
        aggregateRoutes(manifest) { context, aggregate -> commandRoutes(context, aggregate, commands, statuses) }
        // The job commands, which a service calls when its permits list them.
        manifest.contexts.forEach { context -> contextRoutes(context, commands, statuses) }
        // A command route of a served context that is none of the above: a command the caller's contract names and
        // this service does not decide, such as one of a newer version of it. The gateway refuses it as
        // `wow.command.unhandled` with its REJECTED result, which the calling saga takes as a rejection, not as a
        // failure to retry.
        manifest.contexts.forEach { context ->
            POST("/${context.name}/{$AGGREGATE}/${CommandRoutes.ID}/{$COMMAND}") { request ->
                statuses.answering { commands.handle(request, unknownTarget(context.name, request, byId = true)) }
            }
            POST("/${context.name}/{$AGGREGATE}/{$COMMAND}") { request ->
                statuses.answering { commands.handle(request, unknownTarget(context.name, request, byId = false)) }
            }
        }
    }
}

/**
 * The [routes] of each aggregate of [served], found by the path's first two segments, `/{context}/{aggregate}`, in one
 * lookup (performance item 11): WebFlux matches a request against the routes in order, about 50 ns each,
 * so a command of the 50th of 50 aggregates (18 routes each) took 46 µs to match, against under 1 µs within its
 * aggregate's routes. When some command or query route of [served] is not under its aggregate's
 * `/{context}/{aggregate}/` (a Manifest read from elsewhere may say any path), the routes stay one list, in order, so
 * that no request matches another route than before.
 */
private fun CoRouterFunctionDsl.aggregateRoutes(
    served: Manifest,
    routes: CoRouterFunctionDsl.(context: String, aggregate: AggregateManifest) -> Unit,
) {
    val prefixed = served.contexts.all { context ->
        context.aggregates.all { aggregate ->
            val prefix = "/${context.name}/${aggregate.name}/"
            (aggregate.commands.flatMap { it.routes } + aggregate.queries.flatMap { it.routes })
                .all { it.path.startsWith(prefix) }
        }
    }
    if (!prefixed) {
        served.contexts.forEach { context -> context.aggregates.forEach { routes(context.name, it) } }
        return
    }
    val byContext = served.contexts.groupBy { it.name }.mapValues { (name, contexts) ->
        contexts.flatMap { it.aggregates }.associate { aggregate ->
            aggregate.name to coRouter { routes(name, aggregate) }
        }
    }
    add(AggregateRouter(byContext))
}

/** Routes a request to the routes of the aggregate its path's first two segments name, if any. */
private class AggregateRouter(
    private val byContext: Map<String, Map<String, RouterFunction<ServerResponse>>>,
) : RouterFunction<ServerResponse> {
    override fun route(request: ServerRequest): Mono<HandlerFunction<ServerResponse>> {
        // A separator before each segment: "/", context, "/", aggregate, ...
        val elements = request.requestPath().pathWithinApplication().elements()
        val context = (elements.getOrNull(1) as? PathContainer.PathSegment)?.valueToMatch()
        val aggregate = (elements.getOrNull(AGGREGATE_SEGMENT) as? PathContainer.PathSegment)?.valueToMatch()
        val routes = byContext[context]?.get(aggregate) ?: return Mono.empty()
        return routes.route(request)
    }

    private companion object {
        const val AGGREGATE_SEGMENT = 3
    }
}

private const val AGGREGATE = "aggregate"
private const val COMMAND = "command"

/** The target of a call [request] to a command route of [context] that no route of the Manifest matched. */
private fun unknownTarget(context: String, request: ServerRequest, byId: Boolean): CommandTarget =
    CommandTarget(context, request.pathVariable(AGGREGATE), request.pathVariable(COMMAND), byId)

/** The routes of [aggregate]'s commands, its system commands among them. */
private fun CoRouterFunctionDsl.commandRoutes(
    context: String,
    aggregate: AggregateManifest,
    handler: CommandHandler,
    statuses: ErrorStatuses,
) {
    aggregate.commands.forEach { command ->
        command.routes.forEach { route ->
            val target = if (command.system) {
                // A lifecycle command of an aggregate whose id is its owner's has a route by no id, the caller's own.
                systemTarget(context, aggregate.name, command.name, route.path.contains(CommandRoutes.ID))
            } else {
                CommandTarget(
                    context,
                    aggregate.name,
                    command.name,
                    route.path.contains(CommandRoutes.ID),
                    creates = command.creation == Creation.REQUIRED,
                )
            }
            route(route, target, handler, statuses)
        }
    }
}

/**
 * The routes of [context]'s own commands ([ContextManifest.commands]): the job commands of its built-in job aggregate
 * when it has jobs, `POST /{context}/wow.schedule/{id}/enable`, `.../reschedule` and `.../disable`, the instance's id
 * being the job's name.
 */
private fun CoRouterFunctionDsl.contextRoutes(
    context: ContextManifest,
    handler: CommandHandler,
    statuses: ErrorStatuses,
) {
    context.commands.forEach { command ->
        val target = systemTarget(context.name, null, command.name)
        command.routes.forEach { route(it, target, handler, statuses) }
    }
}

/** [route] sends [target] through [handler]. */
private fun CoRouterFunctionDsl.route(
    route: RouteManifest,
    target: CommandTarget,
    handler: CommandHandler,
    statuses: ErrorStatuses,
) {
    (method(HttpMethod.valueOf(route.method)) and path(route.path)).invoke { request ->
        statuses.answering { handler.handle(request, target) }
    }
}

/**
 * The command the route of the system command [name] sends, of [aggregate] (null for a context's command) in
 * [context]: a lifecycle command to the aggregate of the path's id; the snapshot rebuild to the instance of the
 * built-in rebuild aggregate named by [aggregate], whose snapshots it rebuilds; a job command to the instance of the
 * built-in job aggregate the path's id names. Each is sent through the gateway like any command, so its authorizer
 * (CoSec's, by the action of the route) decides who may send it in their tenant.
 */
private fun systemTarget(
    context: String,
    aggregate: String?,
    name: String,
    byId: Boolean = true
): CommandTarget = when {
    name == SnapshotRebuilds.REBUILD ->
        CommandTarget(context, SnapshotRebuilds.AGGREGATE, name, byId = false, id = checkNotNull(aggregate))

    name in ScheduleJobs.COMMANDS -> CommandTarget(context, ScheduleJobs.AGGREGATE, name, byId = true)
    else -> CommandTarget(
        context,
        checkNotNull(aggregate) { "The system command $name has no aggregate." },
        name,
        byId = byId,
    )
}

/**
 * The query routes of [aggregate]'s query models ([AggregateManifest.queries]) that [queries] serves:
 * `POST` runs a query, `GET` answers the descriptor.
 */
private fun CoRouterFunctionDsl.queryRoutes(
    type: AggregateType,
    aggregate: AggregateManifest,
    queries: HttpQueries,
    handler: QueryHandler,
) {
    aggregate.queries.forEach { query ->
        if (!queries.serves(type, query.model)) return@forEach
        val target = QueryTarget(type, query.model)
        query.routes.forEach { route ->
            when (route.method) {
                QueryRoutes.GET -> GET(route.path) { request -> handler.describe(request, target) }
                else -> POST(route.path) { request -> handler.query(request, target) }
            }
        }
    }
}

/** The dataset routes: the list, and each dataset's query and descriptor by its path variables. */
private fun CoRouterFunctionDsl.datasetRoutes(handler: QueryHandler) {
    GET(WowPaths.DATASETS) { request -> handler.listDatasets(request) }
    val path = "${WowPaths.DATASETS}/{$DATASET_CONTEXT}/{$DATASET_NAME}/${QueryRoutes.SEGMENT}"
    POST(path) { request ->
        datasetOf(request)?.let { handler.queryDataset(request, it) } ?: handler.noDataset(request)
    }
    GET(path) { request ->
        datasetOf(request)?.let { handler.describeDataset(request, it) } ?: handler.noDataset(request)
    }
}

private const val DATASET_CONTEXT = "context"
private const val DATASET_NAME = "dataset"

/** The dataset name of [request]'s path, or null when its segments cannot name one. */
private fun datasetOf(request: ServerRequest): AggregateType? = try {
    AggregateType(request.pathVariable(DATASET_CONTEXT), request.pathVariable(DATASET_NAME))
} catch (_: IllegalArgumentException) {
    null
}

/**
 * [block]'s response, or the problem it was answered with: a store that is unavailable where no result carries it
 * (a state read, say) is `wow.storage.unavailable`, as a command's result would be. Any other failure is a 500
 * problem that says nothing of its cause, which is logged: never Spring's bare error page.
 */
@Suppress("TooGenericExceptionCaught") // Whatever else a route throws is answered as a problem, not a bare 500.
private suspend inline fun ErrorStatuses.answering(block: () -> ServerResponse): ServerResponse = try {
    block()
} catch (problem: ProblemException) {
    problem.problem.respond()
} catch (unavailable: StorageUnavailableException) {
    logger.warn(unavailable) { "A request failed: a store is unavailable." }
    problemOf(FrameworkErrors.STORAGE_UNAVAILABLE, unavailable.message!!).respond()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    // A body larger than the codecs take is the client's (413); anything else is a 500.
    val problem = tooLargeProblem(failure) ?: run {
        logger.error(failure) { "A request failed with an unexpected error." }
        Problem.ofStatus(HttpStatus.INTERNAL_SERVER_ERROR, "The request failed with an unexpected error.")
    }
    problem.respond()
}

private val logger = KotlinLogging.logger {}
