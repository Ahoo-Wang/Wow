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

package me.ahoo.wow.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.subscribe.CallFailedException
import me.ahoo.wow.client.wire.WowHeaders
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.call.CommandCaller
import me.ahoo.wow.runtime.call.OutgoingCall
import tools.jackson.core.JacksonException
import java.net.URI
import java.net.http.HttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The synchronous calls of a node's sagas to the HTTP command gateways of other contexts (decision D193): the service
 * mode of the command client ([ClientMode.Service], decision D257), at the base URL [endpoints] gives each context's
 * **internal port** (`wow.clients.contexts.<context>.internal-url`, or `wow.calls.contexts.<context>.url`), the only
 * one that takes a call; its public port refuses `Wow-Call`. Each call goes to the command's route, as the
 * [HttpCommandClient] sends one, and carries:
 *
 * - the command's body as JSON, and its expected version as `If-Match`;
 * - its deterministic request id as `Wow-Request-Id`, so every retry and replay is the same request, which the target
 *   decides once and answers with the same aggregate (decision D191);
 * - `Wow-Call` ([me.ahoo.wow.client.wire.CallHeader]): its command id, the root command's tenant, scope, operator
 *   and causation (decisions D163, D189, D199), the root request's facts that propagate (decision D236 ③), and the
 *   wait context a wait that follows the saga goes on with (decision D190);
 * - the service's credential as `Authorization`, which makes the target trust `Wow-Call`;
 * - the trace the call runs in (decision D174).
 *
 * The target answers once the command is committed: 200 with its result, or a problem+json whose `result` is its
 * REJECTED or FAILED result. Anything else, an error answered without a result (401 or 403 for a credential the target
 * refuses), a connection that fails or no answer within the timeout, is a [CallFailedException]: the saga's handler
 * fails and its subscription retries it, then parks the aggregate. The call itself is not retried here: the
 * subscription's retry budget is the one that counts (decision P.3).
 */
@InternalWowApi
class HttpCommandCaller internal constructor(private val transport: HttpTransport) : CommandCaller {
    /**
     * The caller of the contexts' internal ports at the fixed [endpoints], presenting [credential], on [client], each
     * call answered within [timeout].
     */
    @JvmOverloads
    constructor(
        endpoints: Map<String, URI>,
        credential: ServiceCredential = ServiceCredential.NONE,
        client: HttpClient = client(),
        timeout: Duration = DEFAULT_TIMEOUT,
    ) : this(EndpointResolver.of(emptyMap(), endpoints), credential, client, timeout)

    /** The caller of the contexts' internal ports [endpoints] finds, presenting [credential]. */
    constructor(
        endpoints: EndpointResolver,
        credential: ServiceCredential,
        client: HttpClient,
        timeout: Duration,
    ) : this(HttpTransport(endpoints, ClientMode.Service(credential), client, timeout, RetryPolicy.NONE))

    /**
     * Whether the endpoints give [context] an internal URL (decision D259): the node does not start when a saga calls
     * a context without one, rather than failing the call later. A resolver that discovers its services is asked once,
     * at that check.
     */
    override fun reaches(context: String): Boolean =
        runBlocking { transport.endpoints.resolve(context, Port.INTERNAL) } != null

    @Suppress("TooGenericExceptionCaught") // Whatever fails the exchange is a call that reached no result.
    override suspend fun call(call: OutgoingCall): CommandResult {
        val command = call.command
        val answer = try {
            transport.exchange(requestOf(call)) { false }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw CallFailedException(
                "The call of ${command.id} to ${command.ref.type.context} failed: $failure",
                failure
            )
        }
        return when (answer) {
            is Answer.Ok -> try {
                ClientJson.mapper.readValue(answer.value, CommandResult::class.java)
            } catch (_: JacksonException) {
                null
            }
            is Answer.Problem -> answer.problem.result
        } ?: throw CallFailedException(
            "The call of ${command.id} to ${command.ref.type.context} was answered without a result: " +
                (answer as? Answer.Problem)?.problem?.let { "HTTP ${it.status} ${it.detail.orEmpty()}" }.orEmpty(),
        )
    }

    @InternalWowApi
    companion object {
        /** The default bound of one call: as long as a wait's default timeout (decision D64). */
        val DEFAULT_TIMEOUT: Duration = 10.seconds

        /** The default connect timeout of [client]. */
        val DEFAULT_CONNECT_TIMEOUT: Duration = 2.seconds

        /** A JDK [HttpClient] for the ports of other services: HTTP/1.1, which every server speaks. */
        @JvmStatic
        @JvmOverloads
        fun client(connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT): HttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout.toJavaDuration())
            .build()

        /** The request of [call]: its command's route, body, request id and expected version, and its root context. */
        internal fun requestOf(call: OutgoingCall): PeerRequest {
            val command = call.command
            val ref = command.ref
            val type = ref.type
            val segment = CommandRoutes.segment(call.name)
            val path = if (ref.isAssigned) {
                "/${type.context}/${type.name}/${HttpTransport.encode(ref.id)}/$segment"
            } else {
                "/${type.context}/${type.name}/$segment"
            }
            return PeerRequest(
                context = type.context,
                path = path,
                caller = Caller.ANONYMOUS,
                call = contextOf(call),
                headers = buildMap {
                    put(WowHeaders.REQUEST_ID, command.requestId)
                    command.expectedVersion?.let { put(WowHeaders.IF_MATCH, "\"$it\"") }
                },
                body = ClientJson.write(command.body),
            )
        }

        /**
         * The root command [call] acts for, as `Wow-Call` carries it, with the request facts its command inherited
         * that propagate (decision D236 ③): the target records the root request's application, not this service's.
         */
        internal fun contextOf(call: OutgoingCall): CallContext {
            val command = call.command
            return CallContext(
                commandId = command.id,
                tenantId = command.ref.tenantId,
                scope = command.scope,
                causation = checkNotNull(command.causation),
                operator = command.operator,
                wait = call.wait,
                facts = command.headers,
            )
        }
    }
}
