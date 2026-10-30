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

package me.ahoo.wow.http.ops

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.command.Negotiation
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.RouteAction
import me.ahoo.wow.runtime.admission.ServiceRouteAuthorizer
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.PartitionView
import me.ahoo.wow.runtime.node.SubscriptionView
import me.ahoo.wow.runtime.node.SubscriptionsView
import me.ahoo.wow.runtime.node.subscriptionsView
import me.ahoo.wow.runtime.subscription.OrphanCheckpoints
import me.ahoo.wow.spi.tenant.TenantPolicy
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

/**
 * The operator's read of the node's subscriptions, `GET /wow/ops/subscriptions` ([OpsPaths.SUBSCRIPTIONS]), served
 * with the parked routes ([parkedRoutes]) and read-only: the node's id (`node`); each subscription (`subscriptions`) by
 * its key, `<context>.<name>` ([me.ahoo.wow.runtime.subscription.SubscriptionId.key]), its `group`, its `source`
 * (`log` or `topic`), its `parked` entries as the node's [sampler] last counted them (up to its
 * [NodeSampler.maxParked]; null when it has not), and its `partitions`, each with its `position` and, in one this node
 * runs, `runs`, the `head` and `lag` the sample read, and whether it is `paused` or `throttled`; and the checkpoints its
 * groups hold under names they no longer have (`orphans`: `group`, `name`, `partitions`, `parked`).
 * [ServiceNode.subscriptionsView][me.ahoo.wow.runtime.node.subscriptionsView] says what each holds.
 *
 * The caller is authenticated with [callerResolver] (401 otherwise) and must reach every tenant
 * ([TenantPolicy.confinedTo]), else 403 `wow.auth.forbidden`: the view is the whole service's, of no aggregate and no
 * tenant's data, so neither a command's authorization on an aggregate nor a tenant's filter applies to it. Then
 * [authorizer], the node's [ServiceRouteAuthorizer], decides the route of the reserved context `wow` named
 * `ops.subscriptions` ([RouteAction]), which CoSec decides as `wow:ops.subscriptions`; with none, as on a service
 * route, every such caller reads it. Errors are problems as on the
 * other public routes, a store that is unavailable `wow.storage.unavailable` with `Retry-After`.
 */
@ExperimentalWowApi
@InternalWowApi
fun subscriptionRoutes(
    sampler: NodeSampler,
    manifest: Manifest,
    authorizer: ServiceRouteAuthorizer = ServiceRouteAuthorizer.ALLOW_ALL,
    callerResolver: CallerResolver = CallerResolver.ANONYMOUS,
    options: WowHttpOptions = WowHttpOptions(),
): RouterFunction<ServerResponse> {
    val statuses = ErrorStatuses(manifest, options.errorStatuses)
    val authenticator = Authenticator(callerResolver, statuses)
    return coRouter {
        GET(OpsPaths.SUBSCRIPTIONS) { request ->
            statuses.answering {
                authorize(request, authenticator, authorizer, statuses)
                val view = sampler.node.subscriptionsView(sampler.latest, sampler.maxParked)
                ServerResponse.ok().contentType(
                    MediaType.APPLICATION_JSON
                ).bodyValueAndAwait(HttpJson.write(viewOf(view)))
            }
        }
    }
}

/** The route of the reserved context `wow` the read is authorized as. */
private const val ROUTE = "ops.subscriptions"

private suspend fun authorize(
    request: ServerRequest,
    authenticator: Authenticator,
    authorizer: ServiceRouteAuthorizer,
    statuses: ErrorStatuses,
) {
    val caller = authenticator.authenticate(request)
    Negotiation.checkAcceptable(request, alsoEventStream = false)
    TenantPolicy.confinedTo(caller)?.let { tenant ->
        throw ProblemException(
            statuses.problemOf(
                FrameworkCodes.AUTH_FORBIDDEN,
                "The node's subscriptions are the whole service's: a caller confined to tenant $tenant may not read them.",
            ),
        )
    }
    val action = RouteAction(
        CommandRoutes.RESERVED_CONTEXT,
        ROUTE,
        request.method().name(),
        request.path(),
        caller.tenantId,
        caller.scope
    )
    val decision = authorizer.authorize(caller, action)
    if (decision is Authorization.Denied) {
        throw ProblemException(statuses.problemOf(decision.code, decision.message))
    }
}

/** The node's subscriptions as the route writes them. */
internal fun viewOf(view: SubscriptionsView): Map<String, Any?> = linkedMapOf(
    "node" to view.node,
    "subscriptions" to view.subscriptions.map(::viewOf),
    "orphans" to view.orphans.map(::viewOf),
)

private fun viewOf(view: SubscriptionView): Map<String, Any?> = linkedMapOf(
    "subscription" to view.id.key,
    "group" to view.group,
    "source" to WireNames.of(view.source),
    "parked" to view.parked,
    "partitions" to view.partitions.map(::viewOf),
)

private fun viewOf(view: PartitionView): Map<String, Any?> = buildMap {
    put("partition", view.partition)
    put("position", view.position)
    if (view.runs) {
        put("runs", true)
    }
    view.head?.let { put("head", it) }
    view.lag?.let { put("lag", it) }
    view.paused?.let { put("paused", mapOf("since" to it.since, "reason" to it.reason)) }
    view.throttled?.let { put("throttled", mapOf("limit" to it.limit, "since" to it.since)) }
}

private fun viewOf(orphan: OrphanCheckpoints): Map<String, Any?> = linkedMapOf(
    "group" to orphan.group,
    "name" to orphan.name,
    "partitions" to orphan.positions.map { mapOf("partition" to it.partition, "position" to it.position) },
    "parked" to orphan.parked,
)
