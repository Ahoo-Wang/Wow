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
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.command.RequestHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.query.QueryHandler
import me.ahoo.wow.http.query.QueryTarget
import me.ahoo.wow.http.read.StateHandler
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.admission.QueryTargets
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.policy.QueryOwnership
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.state.StateReads
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

/**
 * The read routes of the services' peer reads (decisions D232, D236, D259), served on the internal port beside the call
 * routes ([callRoutes]): the same paths and answers as the public port's query routes, state reads and dataset routes,
 * for the services that read this one through `wow-client`'s service mode, without the end user's credential.
 *
 * - `POST` and `GET /{context}/{aggregate}/{model}/query` of each query model of the Manifest, 404 for one [queries]
 *   does not serve (never the call routes' unknown command);
 * - `GET /{context}/{aggregate}/{id}/state` (`?version=`, `?at=`) of each aggregate, over [reads];
 * - `POST` and `GET /wow/dataset/{context}/{dataset}/query` of the datasets [queries] has;
 * - `GET /wow/manifest`, the service's Manifest, for a service that calls it by its Manifest alone (decision D257,
 *   `wow.peers.<context>.contract: manifest`): any service may read it, whatever models [permits] list.
 *
 * Each request must come from a [service][Caller.service] identity, which [callerResolver] resolves (the service the
 * request names, [me.ahoo.wow.http.caller.DeclaredServices], decision D300), and carry `Wow-Call`, the root context it
 * reads for; any other, one that names no service too, is answered 403 `wow.auth.forbidden`, and so is a model
 * [permits] does not let the service read
 * ([CallPermits.readRefusal]). The read then runs as the root context's caller: its tenant (the aggregate type's fixed
 * one, when it has one), its owner and space, its operator as the subject and its application (`app_id` of the root
 * request's facts) as the caller's [app][Caller.app], so the scope policy, ABAC and the application's query policies
 * restrict it as they would the root request's, and it is masked alike. Its command id and causation say nothing
 * here. A `Wow-Scope` is refused, as for any caller that may not act across scopes. None of these routes is in the
 * OpenAPI document (decision D226): they are the services' own. The streamed answers are produced in [scope], as
 * [wowRoutes]'s are.
 */
@InternalWowApi
@Suppress("LongParameterList") // One parameter per part of the routes, as wowRoutes takes them.
fun peerReadRoutes(
    manifest: Manifest,
    gateway: CommandGateway,
    callerResolver: CallerResolver,
    queries: HttpQueries,
    reads: StateReads = StateReads.NONE,
    options: WowHttpOptions = WowHttpOptions(),
    permits: CallPermits = CallPermits(emptyMap()),
    masks: PayloadMasks = PayloadMasks(manifest),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): RouterFunction<ServerResponse> {
    val statuses = ErrorStatuses(manifest, options.errorStatuses)
    val roots = PeerRoots(callerResolver, permits, statuses)
    val authenticator =
        Authenticator(CallerResolver { request -> request.attribute(ROOT_CALLER).orElse(null) as Caller? }, statuses)
    val headers = RequestHeaders(statuses)
    // The read side's scope rules are :query's (review R5.C-1), over the tenants commands act in (decision D116).
    val targets = QueryTargets(gateway.tenants, queries.crossTenant)
    val queryHandler = QueryHandler(queries, authenticator, headers, statuses, targets, scope)
    val ownerships = QueryOwnership.of(manifest)
    val states =
        StateHandler(reads, authenticator, headers, statuses, targets, ownerships::ownershipOf, queries, masks)
    val document = HttpJson.write(ManifestTree.write(withTransformedRoutes(manifest, gateway)))
    return coRouter {
        GET(WowPaths.MANIFEST) { request ->
            roots.reading(request, model = null) {
                ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(document)
            }
        }
        manifest.contexts.forEach { context ->
            context.aggregates.forEach { aggregate ->
                val type = AggregateType(context.name, aggregate.name)
                GET("/${context.name}/${aggregate.name}/${CommandRoutes.ID}/${WowPaths.STATE}") { request ->
                    roots.reading(request, "$type.${WowPaths.STATE}") { states.handle(request, type) }
                }
                // Every query model of the Manifest: one this node does not serve is a 404, never a command route's.
                aggregate.queries.forEach { query ->
                    val target = QueryTarget(type, query.model)
                    val model = "$type.${query.model}"
                    val served = queries.gateway(type, query.model) != null
                    query.routes.forEach { route ->
                        when (route.method) {
                            QueryRoutes.GET -> GET(route.path) { request ->
                                roots.reading(request, model) {
                                    if (served) queryHandler.describe(request, target) else notServed(model)
                                }
                            }
                            else -> POST(route.path) { request ->
                                roots.reading(request, model) {
                                    if (served) queryHandler.query(request, target) else notServed(model)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (queries.datasets !== DatasetQueryGateways.NONE) {
            val path = "${WowPaths.DATASETS}/{$DATASET_CONTEXT}/{$DATASET_NAME}/${QueryRoutes.SEGMENT}"
            POST(path) { request ->
                val name = datasetName(request)
                roots.reading(request, name.toString()) { queryHandler.queryDataset(request, name) }
            }
            GET(path) { request ->
                val name = datasetName(request)
                roots.reading(request, name.toString()) { queryHandler.describeDataset(request, name) }
            }
        }
    }
}

private const val DATASET_CONTEXT = "context"
private const val DATASET_NAME = "dataset"

/** The request attribute that holds the root context's caller a peer read runs as. */
private const val ROOT_CALLER = "me.ahoo.wow.http.peerReadCaller"

private suspend fun notServed(model: String): ServerResponse =
    Problem.ofStatus(HttpStatus.NOT_FOUND, "No query of $model is served on this node.").respond()

private fun datasetName(request: ServerRequest): AggregateType =
    AggregateType(request.pathVariable(DATASET_CONTEXT), request.pathVariable(DATASET_NAME))

/** Admits the peer reads: a service's, with `Wow-Call`, of a model it may read; the read runs as the root's caller. */
internal class PeerRoots(
    private val services: CallerResolver,
    private val permits: CallPermits,
    private val statuses: ErrorStatuses,
) {
    /**
     * [read]'s response once [request] is admitted to read [model] (any service's read when null), or the problem it
     * is refused with.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever fails admitting a read is answered as a problem.
    suspend fun reading(request: ServerRequest, model: String?, read: suspend () -> ServerResponse): ServerResponse =
        try {
            request.attributes()[ROOT_CALLER] = rootOf(request, model)
            read()
        } catch (problem: ProblemException) {
            problem.problem.respond()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.error(failure) { "A peer read failed with an unexpected error." }
            Problem.ofFailure(failure).respond()
        }

    /** The caller of the root context [request] reads for, once it may read [model] (any service when null). */
    @Suppress("ThrowsCount") // Each refusal of a read, answered with its own problem.
    suspend fun rootOf(request: ServerRequest, model: String?): Caller {
        val service = services.resolve(request) ?: Caller.ANONYMOUS
        val value = request.headers().firstHeader(WowHeaders.CALL)
        if (!service.service || value == null) {
            throw forbidden(
                "The internal port serves only a service identity's read with ${WowHeaders.CALL}."
            )
        }
        model?.let { permits.readRefusal(service, it) }?.let { throw forbidden(it) }
        val call = try {
            CallHeaders.parse(value)
        } catch (invalid: IllegalArgumentException) {
            throw ProblemException(
                statuses.problemOf(FrameworkCodes.COMMAND_MALFORMED, invalid.message ?: "Wow-Call does not parse.")
            )
        }
        return Caller.of(
            tenantId = call.tenantId,
            scope = call.scope,
            subject = call.operator?.id,
            attributes = call.facts[RequestFacts.APP_ID]?.let { mapOf(Caller.APP to listOf(it)) }.orEmpty(),
        )
    }

    private fun forbidden(detail: String) =
        ProblemException(statuses.problemOf(FrameworkCodes.AUTH_FORBIDDEN, detail))

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
