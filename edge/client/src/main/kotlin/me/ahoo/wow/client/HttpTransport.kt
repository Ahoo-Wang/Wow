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

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.future.await
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.client.wire.CallHeader
import me.ahoo.wow.client.wire.WowHeaders
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.spi.observation.trace
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.Flow.Publisher
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * One request to a peer, the same whatever it asks: the [context] whose service answers it, the [path] under the base
 * URL its port has, the [caller] it acts for and, in service mode, the root context [call] it asserts (`Wow-Call`).
 */
internal class PeerRequest(
    val context: String,
    val path: String,
    val caller: Caller,
    val call: CallContext?,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeout: Duration? = null,
)

/** What a peer answered: a 2xx response's [Ok], or a [Problem]. */
internal sealed interface Answer<out T> {
    class Ok<T>(val value: T) : Answer<T>

    class Problem(val problem: PeerProblem) : Answer<Nothing>
}

/**
 * The HTTP exchanges of the clients with the peers (decision D257), on the JDK [client]: each request goes to the base
 * URL [endpoints] gives its context on the [mode]'s port, asked again for every attempt; it carries the [mode]'s
 * credential (`Authorization` of the service, or the end user's forwarded headers), the root context in service mode
 * (`Wow-Call`), and the trace the coroutine runs in (decision D174); it is answered within [timeout] (the response's
 * head, for a stream) and retried as [retry] says.
 */
internal class HttpTransport(
    val endpoints: EndpointResolver,
    val mode: ClientMode,
    val client: HttpClient,
    val timeout: Duration,
    val retry: RetryPolicy,
) {
    init {
        require(timeout.isPositive()) { "A request's timeout is positive." }
    }

    /** [request] answered as bytes, retried while [retries] says so of its problem. */
    suspend fun exchange(request: PeerRequest, retries: (PeerProblem) -> Boolean): Answer<ByteArray> =
        withRetries(retries) {
            val response = client.sendAsync(
                build(request, JSON_ACCEPT),
                HttpResponse.BodyHandlers.ofByteArray()
            ).await()
            val status = response.statusCode()
            if (status in SUCCESS) {
                Answer.Ok(response.body())
            } else {
                Answer.Problem(Problems.read(status, response.headers(), response.body()))
            }
        }

    /**
     * [request] answered as a `text/event-stream`, its lines once the response's head says it is one, retried while
     * [retries] says so of its problem; a 2xx response of another type (a peer that answered with one JSON document)
     * is its body read whole, as the data of one event.
     */
    suspend fun stream(request: PeerRequest, retries: (PeerProblem) -> Boolean): Answer<Flow<String>> =
        withRetries(retries) {
            val response = client.sendAsync(
                build(request, EVENT_STREAM),
                HttpResponse.BodyHandlers.ofPublisher()
            ).await()
            val status = response.statusCode()
            val body = response.body()
            when {
                status !in SUCCESS -> Answer.Problem(Problems.read(status, response.headers(), body.bytes()))
                response.headers().firstValue(
                    CONTENT_TYPE
                ).orElse("").startsWith(EVENT_STREAM) -> Answer.Ok(body.lines())
                else -> Answer.Ok(asEvent(body.bytes().decodeToString()))
            }
        }

    private suspend fun <T> withRetries(retries: (PeerProblem) -> Boolean, attempt: suspend () -> Answer<T>): Answer<T> {
        var attempts = 1
        while (true) {
            val answer = try {
                attempt()
            } catch (failure: IOException) {
                // A connection that was not made sent nothing: refused, or not answered in time.
                if (!failure.unsent()) throw failure
                delay(retry.delayBefore(attempts, null) ?: throw failure)
                attempts++
                continue
            }
            if (answer is Answer.Problem && retries(answer.problem)) {
                val wait = retry.delayBefore(attempts, answer.problem.retryAfter) ?: return answer
                delay(wait)
                attempts++
                continue
            }
            return answer
        }
    }

    private suspend fun build(request: PeerRequest, accept: String): HttpRequest {
        val base = endpoints.resolve(request.context, mode.port)?.toString()?.trimEnd('/')
            ?: throw IllegalStateException(
                "No URL calls context ${request.context} on its ${mode.port.name.lowercase()} port: set " +
                    "wow.clients.contexts.${request.context}.${if (mode.port == Port.PUBLIC) "url" else "internal-url"}, " +
                    "or give the client an EndpointResolver that finds it (decision D259).",
            )
        val builder = HttpRequest.newBuilder(URI.create(base + request.path))
            .timeout((request.timeout ?: timeout).toJavaDuration())
            .header(ACCEPT, accept)
        when (val mode = mode) {
            is ClientMode.Service -> {
                mode.credential.authorization()?.let { builder.header(AUTHORIZATION, it) }
                request.call?.let { builder.header(WowHeaders.CALL, CallHeader.format(it)) }
            }
            is ClientMode.User -> mode.credential.headers(
                request.caller
            ).forEach { (name, value) -> builder.header(name, value) }
        }
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        currentCoroutineContext().trace.forEach { (name, value) -> builder.header(name, value) }
        val body = request.body
        return if (body == null) {
            builder.GET().build()
        } else {
            builder.header(CONTENT_TYPE, JSON).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        }
    }

    companion object {
        const val JSON: String = "application/json"
        const val PROBLEM_JSON: String = "application/problem+json"
        const val EVENT_STREAM: String = "text/event-stream"
        const val JSON_ACCEPT: String = "$JSON, $PROBLEM_JSON"
        const val CONTENT_TYPE: String = "Content-Type"
        const val ACCEPT: String = "Accept"
        const val AUTHORIZATION: String = "Authorization"
        private val SUCCESS = 200..299

        /** Whether this failure is of a connection that was never made, so the request was not sent. */
        internal fun IOException.unsent(): Boolean = when (this) {
            is ConnectException, is HttpConnectTimeoutException -> true
            else -> false
        }

        /** A path segment, percent-encoded. */
        fun encode(segment: String): String = URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")

        /** [document] as the lines of one server-sent event whose data it is. */
        private fun asEvent(document: String): Flow<String> =
            (document.lines().map { "data: $it" } + "").asFlow()

        /** The whole body this publishes. */
        private suspend fun Publisher<List<ByteBuffer>>.bytes(): ByteArray =
            lines().fold(ByteArrayOutputStream()) { out, line ->
                if (out.size() > 0) out.write('\n'.code)
                out.write(line.toByteArray())
                out
            }.toByteArray()
    }
}
