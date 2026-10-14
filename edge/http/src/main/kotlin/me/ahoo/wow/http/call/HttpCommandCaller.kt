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

package me.ahoo.wow.http.call

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.subscribe.CallFailedException
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.http.command.CallHeader
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.call.CommandCaller
import me.ahoo.wow.runtime.call.OutgoingCall
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.observation.trace
import tools.jackson.core.JacksonException
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The credential a service presents on its synchronous calls (decision D193): the value of the `Authorization` header,
 * such as `Bearer <token>`, asked for on every call so that one issued for a while (a CoSec service principal's token)
 * can be renewed; null sends none.
 */
fun interface ServiceCredential {
    suspend fun authorization(): String?

    companion object {
        /** Presents no credential: the target serves the call only if it trusts its network. */
        @JvmField
        val NONE: ServiceCredential = ServiceCredential { null }

        /** Presents `Bearer <token>`, a service token the targets trust. */
        @JvmStatic
        fun bearer(token: String): ServiceCredential {
            require(token.isNotBlank()) { "A service token is not blank." }
            val authorization = "Bearer $token"
            return ServiceCredential { authorization }
        }
    }
}

/**
 * Calls the HTTP command gateways of other contexts for the sagas of a node (decision D193), on the routes their
 * Manifests give (`POST /{context}/{aggregate}/{id}/{command}`, or without the id for a creation that leaves it to the
 * target, decision D191), at the base URL [endpoints] gives each context (`wow.calls.contexts.<context>.url`).
 *
 * Each call carries:
 * - the command's body as JSON, and its expected version as `If-Match`;
 * - its deterministic request id as `Wow-Request-Id`, so every retry and replay is the same request, which the target
 *   decides once and answers with the same aggregate (decision D191);
 * - `Wow-Call` ([CallHeader]): its command id, the root command's tenant, scope, operator and causation (decisions
 *   D163, D189, D199), and the wait context a wait that follows the saga goes on with (decision D190);
 * - the service's [credential] as `Authorization`, which makes the target trust `Wow-Call`;
 * - the trace the call runs in (decision D174).
 *
 * The target answers once the command is committed: 200 with its result, or a problem+json whose `result` is its
 * REJECTED or FAILED result. Anything else, an error answered without a result (401 or 403 for a credential the target
 * refuses), a connection that fails or no answer within [timeout], is a [CallFailedException]: the saga's handler fails
 * and its subscription retries it, then parks the aggregate.
 */
class HttpCommandCaller @JvmOverloads constructor(
    endpoints: Map<String, URI>,
    private val credential: ServiceCredential = ServiceCredential.NONE,
    private val client: HttpClient = client(),
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : CommandCaller {
    private val endpoints: Map<String, String> = endpoints.mapValues { (context, uri) ->
        require(uri.scheme == "http" || uri.scheme == "https") { "The URL of context $context is http or https: $uri" }
        uri.toString().trimEnd('/')
    }

    init {
        require(timeout.isPositive()) { "A call's timeout is positive." }
    }

    override suspend fun call(call: OutgoingCall): CommandResult {
        val command = call.command
        val context = command.ref.type.context
        val base = endpoints[context] ?: throw CallFailedException(
            "No URL calls context $context for ${command.id}: set wow.calls.contexts.$context.url (decision D193).",
        )
        val request = HttpRequest.newBuilder(URI.create(base + pathOf(call)))
            .timeout(timeout.toJavaDuration())
            .header(CONTENT_TYPE, JSON)
            .header(ACCEPT, "$JSON, $PROBLEM_JSON")
            .header(WowHeaders.REQUEST_ID, command.requestId)
            .header(WowHeaders.CALL, CallHeader.format(contextOf(call)))
            .apply {
                command.expectedVersion?.let { header(WowHeaders.IF_MATCH, "\"$it\"") }
                credential.authorization()?.let { header(AUTHORIZATION, it) }
                currentCoroutineContext().trace.forEach { (name, value) -> header(name, value) }
            }
            .POST(HttpRequest.BodyPublishers.ofByteArray(WowJson.mapper.writeValueAsBytes(command.body)))
            .build()
        val response = send(request, call)
        return resultOf(response, call)
    }

    @Suppress("TooGenericExceptionCaught") // Whatever fails the exchange is a call that reached no result.
    private suspend fun send(request: HttpRequest, call: OutgoingCall): HttpResponse<ByteArray> = try {
        client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).await()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw CallFailedException("The call of ${call.command.id} to ${request.uri()} failed: $failure", failure)
    }

    /** The result [response] answers [call] with, or a [CallFailedException] when it holds none. */
    private fun resultOf(response: HttpResponse<ByteArray>, call: OutgoingCall): CommandResult {
        val status = response.statusCode()
        val body = response.body()
        val result = try {
            if (status in SUCCESS) {
                HttpJson.mapper.readValue(body, CommandResult::class.java)
            } else {
                HttpJson.mapper.readValue(body, ProblemBody::class.java).result
            }
        } catch (_: JacksonException) {
            null
        } catch (_: IOException) {
            null
        }
        return result ?: throw CallFailedException(
            "The call of ${call.command.id} to ${call.command.ref.type.context} was answered HTTP $status without a " +
                "result: ${body.decodeToString().take(MAX_DETAIL)}",
        )
    }

    /** The part of a problem+json body a call reads: the command's result, when the problem has one. */
    private data class ProblemBody(val result: CommandResult? = null)

    companion object {
        /** The default bound of one call: as long as a wait's default timeout (decision D64). */
        val DEFAULT_TIMEOUT: Duration = 10.seconds

        /** The default connect timeout of [client]. */
        val DEFAULT_CONNECT_TIMEOUT: Duration = 2.seconds

        private const val CONTENT_TYPE = "Content-Type"
        private const val ACCEPT = "Accept"
        private const val AUTHORIZATION = "Authorization"
        private const val JSON = "application/json"
        private const val PROBLEM_JSON = "application/problem+json"
        private const val MAX_DETAIL = 512
        private val SUCCESS = 200..299

        /** A JDK [HttpClient] for the public ports of other services: HTTP/1.1, which every public server speaks. */
        @JvmStatic
        @JvmOverloads
        fun client(connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT): HttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout.toJavaDuration())
            .build()

        /** The path of [call]'s route: with its aggregate's id, or without one for a creation that leaves it to the target. */
        internal fun pathOf(call: OutgoingCall): String {
            val ref = call.command.ref
            val type = ref.type
            val segment = CommandRoutes.segment(call.name)
            return if (ref.isAssigned) {
                "/${type.context}/${type.name}/${encode(ref.id)}/$segment"
            } else {
                "/${type.context}/${type.name}/$segment"
            }
        }

        /** The root command [call] acts for, as `Wow-Call` carries it. */
        internal fun contextOf(call: OutgoingCall): CallContext {
            val command = call.command
            return CallContext(
                commandId = command.id,
                tenantId = command.ref.tenantId,
                scope = command.scope,
                causation = checkNotNull(command.causation),
                operator = command.operator,
                wait = call.wait,
            )
        }

        private fun encode(segment: String): String = URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")
    }
}
