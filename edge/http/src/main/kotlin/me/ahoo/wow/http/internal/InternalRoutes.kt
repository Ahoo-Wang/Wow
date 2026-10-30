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
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.caller.DeclaredServices
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.ForwardCommand
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.Refusal
import me.ahoo.wow.runtime.wait.WaitRegistry
import org.springframework.core.io.buffer.DataBufferLimitException
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
 * The routes of the internal port:
 *
 * - `POST` [InternalHttp.FORWARD_PATH] reads a [ForwardCommand] ([ClusterCodec]) and answers [forward]'s
 *   [ForwardReply]. The forward runs in [scope], apart from the request, so a sender that gives up (its deadline
 *   passed, its stream reset) does not stop the execution it started, as on the loopback channel.
 * - `POST` [InternalHttp.SIGNALS_PATH] reads a [WaitSignal], or a batch of them ([ClusterCodec.decodeSignals]),
 *   hands each to [signals] and answers 204, whether or not a
 *   wait took it: a signal is an observation, its sender does not act on the answer.
 *
 * A body that is not such a message is answered 400. A forward larger than the port's codecs take is refused
 * ([Refusal.TOO_LARGE]): this node surely did not run it, so its sender runs it; such a signal is
 * answered 413. A node's [forward] is its [me.ahoo.wow.runtime.node.ServiceNode.forwardHandler], which hands a command
 * to the endpoint of its context.
 *
 * Each request names the service it comes from (`Wow-Service`), a statement the internal port takes on
 * its network's word: the port is reachable by the platform's own pods alone. With [service], this node's, a forward
 * is taken only from a node of [service], else answered 401 `wow.auth.unauthenticated` (it names none) or 403
 * `wow.auth.forbidden` (another one) before its body is read, so a node configured for another service's cluster is
 * caught. A signal is taken from any service that names itself: a wait that crossed into another service is
 * signalled by that service's nodes. Without [service] both routes take every request.
 *
 * With [standby], the node's operator routes of a migration's cut-over, from a node of [service] only:
 * `GET` [InternalHttp.STANDBY_PATH] answers whether the node is on standby and its [StandbyControl.Status]; `POST`
 * [InternalHttp.ACTIVATE_PATH] activates it, the body naming who activates it and why, `{"by": "…", "reason": "…"}`
 * (`by` required, for the audit), and answers its standby after.
 */
@InternalWowApi
fun internalRoutes(
    forward: ClusterChannelHandler,
    signals: (WaitSignal) -> Unit,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    service: String? = null,
    standby: StandbyControl? = null,
): RouterFunction<ServerResponse> = coRouter {
    POST(InternalHttp.FORWARD_PATH) { request ->
        request.refusal(service, anyService = false)?.let { return@POST it.respond() }
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
        request.refusal(service, anyService = true)?.let { return@POST it.respond() }
        val batch = try {
            request.decode(ClusterCodec::decodeSignals) ?: return@POST malformed()
        } catch (_: DataBufferLimitException) {
            return@POST ServerResponse.status(CONTENT_TOO_LARGE).buildAndAwait()
        }
        batch.forEach(signals)
        ServerResponse.noContent().buildAndAwait()
    }
    if (standby != null) {
        GET(InternalHttp.STANDBY_PATH) { request ->
            request.refusal(service, anyService = false)?.let { return@GET it.respond() }
            ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(standby.status())
        }
        POST(InternalHttp.ACTIVATE_PATH) { request ->
            request.refusal(service, anyService = false)?.let { return@POST it.respond() }
            val body = request.decode { HttpJson.mapper.readTree(it) }
            val by = body?.get("by")?.takeIf { it.isString }?.stringValue()?.takeIf(String::isNotBlank)
                ?: return@POST Problem(
                    HttpStatus.BAD_REQUEST.value(),
                    HttpStatus.BAD_REQUEST.reasonPhrase,
                    "An activation names who activates the node, for the audit: {\"by\": \"…\", \"reason\": \"…\"}.",
                    FrameworkCodes.COMMAND_MALFORMED,
                ).respond()
            val reason = body.get("reason")?.takeIf { it.isString }?.stringValue()
            ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(standby.activate(by, reason))
        }
    }
}

/** A node's standby, as its operator routes see it. */
@InternalWowApi
interface StandbyControl {
    /** The node's standby now: whether it is on standby, and when and by whom it was activated. */
    fun status(): Status

    /** Activates the node, [by] naming who and [reason] why. */
    suspend fun activate(by: String, reason: String?): Status

    /** A node's standby: [standby] now, and its last activation ([activatedAt], [activatedBy], [reason]). */
    @InternalWowApi
    data class Status(
        val node: String,
        val standby: Boolean,
        val activatedAt: Long? = null,
        val activatedBy: String? = null,
        val reason: String? = null,
    )
}

/** The routes of the internal port, its signals taken by [registry], the waits started on this node. */
@InternalWowApi
fun internalRoutes(
    forward: ClusterChannelHandler,
    registry: WaitRegistry,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    service: String? = null,
    standby: StandbyControl? = null,
): RouterFunction<ServerResponse> = internalRoutes(forward, { registry.signal(it) }, scope, service, standby)

private val logger = KotlinLogging.logger {}

/**
 * Why this request may not use a route of [service]'s nodes, or null when it may: it names [service] (`Wow-Service`),
 * or with [anyService] any service, or there is no [service]. A request that names none is 401, one that names
 * another service 403.
 */
private fun ServerRequest.refusal(service: String?, anyService: Boolean): Problem? {
    service ?: return null
    val named = DeclaredServices.nameOf(this)
    if (named == null) {
        logger.debug { "A request on ${path()} names no service." }
        return Problem(
            HttpStatus.UNAUTHORIZED.value(),
            HttpStatus.UNAUTHORIZED.reasonPhrase,
            "The internal port takes this request from a service that names itself (${WowHeaders.SERVICE}).",
            FrameworkCodes.AUTH_UNAUTHENTICATED,
        )
    }
    if (anyService || named == service) {
        return null
    }
    return forbidden(this, "Only the nodes of service $service use ${path()}; the request names service $named.")
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
