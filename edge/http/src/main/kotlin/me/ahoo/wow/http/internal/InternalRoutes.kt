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
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.ForwardCommand
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.Refusal
import me.ahoo.wow.runtime.wait.WaitRegistry
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
 * - `POST` [InternalHttp.SIGNALS_PATH] reads a [WaitSignal], hands it to [signals] and answers 204, whether or not a
 *   wait took it: a signal is an observation, its sender does not act on the answer (design 04 §6.3).
 *
 * A body that is not such a message is answered 400. The routes are not authenticated: the internal port is reachable
 * only inside the cluster (D35).
 */
fun internalRoutes(
    forward: ClusterChannelHandler,
    signals: (WaitSignal) -> Unit,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): RouterFunction<ServerResponse> = coRouter {
    POST(InternalHttp.FORWARD_PATH) { request ->
        val command = request.decode(ClusterCodec::decodeCommand) ?: return@POST malformed()
        val reply = scope.async { forward.forward(command) }.await()
        ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(ClusterCodec.encode(reply))
    }
    POST(InternalHttp.SIGNALS_PATH) { request ->
        val signal = request.decode(SignalCodec::decode) ?: return@POST malformed()
        signals(signal)
        ServerResponse.noContent().buildAndAwait()
    }
}

/** The routes of the internal port, its signals taken by [registry], the waits started on this node. */
fun internalRoutes(
    forward: ClusterChannelHandler,
    registry: WaitRegistry,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): RouterFunction<ServerResponse> = internalRoutes(forward, { registry.signal(it) }, scope)

/**
 * The cluster channel handler of a node that hosts several contexts (decision D72): each context has its own
 * [me.ahoo.wow.runtime.cluster.ForwardEndpoint], found in [endpoints] by the context of the forwarded command's
 * aggregate. A context this node does not host is refused as [Refusal.UNKNOWN_TYPE], as an endpoint refuses an
 * aggregate it does not know: the sender then runs the command itself (decision D78).
 */
class ContextDispatchingHandler(private val endpoints: Map<String, ClusterChannelHandler>) : ClusterChannelHandler {
    override suspend fun forward(request: ForwardCommand): ForwardReply =
        endpoints[request.command.ref.type.context]?.forward(request) ?: ForwardReply.refused(Refusal.UNKNOWN_TYPE)
}

private val logger = KotlinLogging.logger {}

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
