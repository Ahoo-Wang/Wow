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

package me.ahoo.wow.http.internal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.ForwardCommand
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.Refusal
import me.ahoo.wow.runtime.wait.WaitRegistry
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import tools.jackson.core.JacksonException

/**
 * The routes of the internal port (decision D109):
 *
 * - `POST` [InternalHttp.FORWARD_PATH] reads a [ForwardCommand] ([ClusterCodec]) and answers [forward]'s
 *   [ForwardReply]. The forward runs in [scope], apart from the request, so a sender that gives up (its deadline
 *   passed, its stream reset) does not stop the execution it started, as on the loopback channel.
 * - `POST` [InternalHttp.SIGNALS_PATH] reads a [WaitSignal] ([ClusterCodec]), hands it to [signals] and answers 204, whether or not a
 *   wait took it: a signal is an observation, its sender does not act on the answer (design 04 §6.3).
 *
 * A body that is not such a message is answered 400. A forward larger than the port's codecs take is refused
 * ([Refusal.TOO_LARGE]): this node surely did not run it, so its sender runs it (review A3-9); such a signal is
 * answered 413. A node's [forward] is its [me.ahoo.wow.runtime.node.ServiceNode.forwardHandler], which hands a command
 * to the endpoint of its context.
 *
 * Both routes are the service's own (decision D226, review A3-1): with [tokens], a forward is taken only with one of
 * them as [InternalHttp.CLUSTER_TOKEN], else answered 401 `wow.auth.unauthenticated` (no token) or 403
 * `wow.auth.forbidden` (another one) before its body is read. A signal is taken with one of them too, or from a
 * [service][me.ahoo.wow.api.caller.Caller.service] identity [services] resolves (a service token or a CoSec service
 * principal, as on the call routes): a wait that crossed into another service is signalled by that service's nodes,
 * which hold no token of this one. Without [tokens] (development) both routes take every request.
 */
@InternalWowApi
fun internalRoutes(
    forward: ClusterChannelHandler,
    signals: (WaitSignal) -> Unit,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    tokens: ClusterTokens = ClusterTokens.NONE,
    services: CallerResolver? = null,
): RouterFunction<ServerResponse> = coRouter {
    POST(InternalHttp.FORWARD_PATH) { request ->
        tokens.refusal(request, null)?.let { return@POST it.respond() }
        val command = try {
            request.decode(ClusterCodec::decodeCommand) ?: return@POST malformed()
        } catch (tooLarge: DataBufferLimitException) {
            logger.warn { "A forward on ${request.path()} is larger than the internal port takes: ${tooLarge.message}" }
            return@POST ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .bodyValueAndAwait(ClusterCodec.encode(ForwardReply.refused(Refusal.TOO_LARGE)))
        }
        val reply = scope.async { forward.forward(command) }.await()
        ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(ClusterCodec.encode(reply))
    }
    POST(InternalHttp.SIGNALS_PATH) { request ->
        tokens.refusal(request, services)?.let { return@POST it.respond() }
        val signal = try {
            request.decode(ClusterCodec::decodeSignal) ?: return@POST malformed()
        } catch (_: DataBufferLimitException) {
            return@POST ServerResponse.status(CONTENT_TOO_LARGE).buildAndAwait()
        }
        signals(signal)
        ServerResponse.noContent().buildAndAwait()
    }
}

/** The routes of the internal port, its signals taken by [registry], the waits started on this node. */
@InternalWowApi
fun internalRoutes(
    forward: ClusterChannelHandler,
    registry: WaitRegistry,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    tokens: ClusterTokens = ClusterTokens.NONE,
    services: CallerResolver? = null,
): RouterFunction<ServerResponse> = internalRoutes(forward, { registry.signal(it) }, scope, tokens, services)

private val logger = KotlinLogging.logger {}

/**
 * Why [request] may not use a cluster route, or null when it may: it carries one of these tokens, or there are none;
 * else, on a route [services] opens, it comes from a service identity. Without a credential it is 401, with one that
 * is not accepted 403.
 */
private suspend fun ClusterTokens.refusal(request: ServerRequest, services: CallerResolver?): Problem? {
    if (isEmpty) {
        return null
    }
    val token = request.headers().firstHeader(InternalHttp.CLUSTER_TOKEN)
    if (token != null) {
        return if (accepts(token)) null else forbidden(request, "The cluster token is not this service's.")
    }
    if (services != null && services.resolve(request)?.service == true) {
        return null
    }
    if (request.headers().firstHeader(HttpHeaders.AUTHORIZATION) != null) {
        val who = if (services == null) "the nodes of this service" else "this service's nodes, or a service it trusts,"
        return forbidden(request, "Only $who use ${request.path()}: a service token opens the call routes alone.")
    }
    logger.debug { "A request on ${request.path()} carries no cluster token." }
    return Problem(
        HttpStatus.UNAUTHORIZED.value(),
        HttpStatus.UNAUTHORIZED.reasonPhrase,
        "The internal port takes this request with the service's cluster token alone (${InternalHttp.CLUSTER_TOKEN}).",
        FrameworkCodes.UNAUTHENTICATED,
    )
}

private fun forbidden(request: ServerRequest, detail: String): Problem {
    logger.warn { "Refused a request on ${request.path()}: $detail" }
    return Problem(
        HttpStatus.FORBIDDEN.value(),
        HttpStatus.FORBIDDEN.reasonPhrase,
        detail,
        FrameworkCodes.AUTH_FORBIDDEN
    )
}

/** The body read by [decode], or null when it is missing or not that message. */
@Suppress("TooGenericExceptionCaught") // A JSON `null` fails the codec's non-null result check.
private suspend fun <T : Any> ServerRequest.decode(decode: (ByteArray) -> T): T? {
    val bytes = awaitBodyOrNull<ByteArray>() ?: return null
    return try {
        decode(bytes)
    } catch (malformed: JacksonException) {
        logger.debug { "Malformed body on ${path()}: ${malformed.originalMessage}" }
        null
    } catch (_: NullPointerException) {
        logger.debug { "A null body on ${path()}." }
        null
    }
}

private suspend fun malformed(): ServerResponse = ServerResponse.badRequest().buildAndAwait()

private const val CONTENT_TOO_LARGE = 413
