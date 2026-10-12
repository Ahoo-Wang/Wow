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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.command.CommandHandler
import me.ahoo.wow.http.command.CommandTarget
import me.ahoo.wow.http.command.Negotiation
import me.ahoo.wow.http.command.RequestHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.http.read.StateHandler
import me.ahoo.wow.http.read.StateReads
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.runtime.admission.CommandGateway
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.CoRouterFunctionDsl
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

/**
 * The routes of the public port (roadmap 5b.2; decisions D16, D30, D92–D104, D112, D113), materialized from
 * [manifest] alone:
 *
 * - every route of every command (`CommandManifest.routes`, decision D104) sends that command through [gateway];
 * - the lifecycle commands each aggregate lists (decision D113): `DELETE /{context}/{aggregate}/{id}` sends
 *   `DeleteAggregate`, `POST /{context}/{aggregate}/{id}/wow.lifecycle.<kebab>` the others;
 * - `GET /{context}/{aggregate}/{id}/state` and `?version=` read [reads] (decision D112);
 * - `GET /wow/manifest` answers [manifest], unless [WowHttpOptions.manifestEndpoint] is off;
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
): RouterFunction<ServerResponse> {
    val statuses = ErrorStatuses(manifest, options.errorStatuses)
    val authenticator = Authenticator(callerResolver, statuses)
    val headers = RequestHeaders(statuses)
    val commands = CommandHandler(
        gateway = gateway,
        authenticator = authenticator,
        headers = headers,
        statuses = statuses,
        options = options,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val states = StateHandler(reads, authenticator, headers, statuses)
    val manifestBody = HttpJson.write(ManifestTree.write(manifest))
    return coRouter {
        if (options.manifestEndpoint) {
            GET(WowPaths.MANIFEST) { request ->
                answering {
                    authenticator.authenticate(request)
                    Negotiation.checkAcceptable(request, alsoEventStream = false)
                    ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(manifestBody)
                }
            }
        }
        manifest.contexts.forEach { context ->
            context.aggregates.forEach { aggregate ->
                commandRoutes(context.name, aggregate, commands)
                val type = AggregateType(context.name, aggregate.name)
                GET("/${context.name}/${aggregate.name}/${CommandRoutes.ID}/${WowPaths.STATE}") { request ->
                    answering { states.handle(request, type) }
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

/** The routes of [aggregate]'s commands and of the lifecycle commands it lists. */
private fun CoRouterFunctionDsl.commandRoutes(context: String, aggregate: AggregateManifest, handler: CommandHandler) {
    aggregate.commands.forEach { command ->
        command.routes.forEach { route ->
            val target = CommandTarget(context, aggregate.name, command.name, route.path.contains(CommandRoutes.ID))
            (method(HttpMethod.valueOf(route.method)) and path(route.path)).invoke { request ->
                answering { handler.handle(request, target) }
            }
        }
    }
    val base = "/$context/${aggregate.name}/${CommandRoutes.ID}"
    aggregate.lifecycle.forEach { name ->
        val target = CommandTarget(context, aggregate.name, name, byId = true)
        if (name == Lifecycle.DELETE_AGGREGATE) {
            DELETE(base) { request -> answering { handler.handle(request, target) } }
        } else {
            POST("$base/${CommandRoutes.segment(name)}") { request -> answering { handler.handle(request, target) } }
        }
    }
}

/** [block]'s response, or the problem it was answered with. */
private suspend inline fun answering(block: () -> ServerResponse): ServerResponse = try {
    block()
} catch (problem: ProblemException) {
    problem.problem.respond()
}
