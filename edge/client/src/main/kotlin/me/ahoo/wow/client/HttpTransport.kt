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
import me.ahoo.wow.api.wire.CallWire
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.spi.observation.trace
import me.ahoo.wow.spi.wire.CallWires
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Flow.Publisher
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * One request to a peer, the same whatever it asks: the [context] whose service answers it, the [path] under the base
 * URL its port has, the [caller] it acts for and, in service mode, the root context [call] it asserts (`Wow-Call`).
 * A request with a [body] is sent with its route's [method], `POST` by default; one without is a `GET`.
 */
internal class PeerRequest(
    val context: String,
    val path: String,
    val caller: Caller,
    val call: CallWire?,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeout: Duration? = null,
    val method: String = "POST",
)

/** What a peer answered: a 2xx response's [Ok], or a [Problem]. */
internal sealed interface Answer<out T> {
    class Ok<T>(val value: T) : Answer<T>

    class Problem(val problem: PeerProblem) : Answer<Nothing>
}

/**
 * The HTTP exchanges of the clients with the peers, on the JDK [client]: each request goes to the base
 * URL [endpoints] gives its context on the [mode]'s port, asked again for every attempt; it carries, in service mode,
 * the service's name (`Wow-Service`) and the root context (`Wow-Call`), in user mode the end user's
 * forwarded credential headers, and the trace the coroutine runs in; it is answered within its
 * context's timeout of [timeouts], else [timeout] (the response's head, for a stream), and retried as [retry] says.
 */
internal class HttpTransport(
    val endpoints: EndpointResolver,
    val mode: ClientMode,
    val client: HttpClient,
    val timeout: Duration,
    val retry: RetryPolicy,
    val timeouts: Map<String, Duration> = emptyMap(),
) {
    init {
        require(timeout.isPositive()) { "A request's timeout is positive." }
        require(timeouts.values.all { it.isPositive() }) { "A request's timeout is positive: $timeouts." }
    }

    /** How long a request to [context] may take until its peer answers it. */
    fun timeoutOf(context: String): Duration = timeouts[context] ?: timeout

    /** [request] answered as bytes, retried while [retries] says so of its problem. */
    suspend fun exchange(request: PeerRequest, retries: (PeerProblem) -> Boolean): Answer<ByteArray> =
        withRetries(request, retries) { base ->
            val response = client.sendAsync(
                build(request, base, JSON_ACCEPT),
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
        withRetries(request, retries) { base ->
            val response = client.sendAsync(
                build(request, base, EVENT_STREAM),
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

    /**
     * [attempt] at [request]'s targets, retried as [retry] says: each attempt goes through the targets of its context
     * ([EndpointResolver.resolveAll]) from the next one in turn, and fails over to the next target on a connection
     * that was not made or on a node that refused before handling ([PeerProblem.refusedBeforeHandling]), each target at
     * most once, without spending a retry. When every target failed to connect, the resolver hears it
     * ([EndpointResolver.unreachable]). What the last target answered is then retried while [retries] says so.
     */
    private suspend fun <T> withRetries(
        request: PeerRequest,
        retries: (PeerProblem) -> Boolean,
        attempt: suspend (base: String) -> Answer<T>,
    ): Answer<T> {
        var attempts = 1
        while (true) {
            val answer = try {
                failover(request, attempt)
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

    /** One attempt at [request]'s targets, in turn, failing over as [withRetries] says. */
    private suspend fun <T> failover(request: PeerRequest, attempt: suspend (base: String) -> Answer<T>): Answer<T> {
        val targets = targetsOf(request.context)
        var unsent: IOException? = null
        var refused: Answer<T>? = null
        for (base in targets) {
            val answer = try {
                attempt(base)
            } catch (failure: IOException) {
                if (!failure.unsent()) throw failure
                unsent = failure
                continue
            }
            if (answer is Answer.Problem && answer.problem.refusedBeforeHandling) {
                refused = answer
                continue
            }
            return answer
        }
        refused?.let { return it }
        endpoints.unreachable(request.context, mode.port)
        throw checkNotNull(unsent)
    }

    /** The targets of [context], each a base URL without its trailing slash, from the next one in turn. */
    private suspend fun targetsOf(context: String): List<String> {
        val targets = endpoints.resolveAll(context, mode.port).map { it.toString().trimEnd('/') }
        check(targets.isNotEmpty()) {
            "No URL calls context $context on its ${mode.port.name.lowercase()} port: set " +
                "wow.peers.$context.${if (mode.port == Port.PUBLIC) "public-url" else "url"}, " +
                "or give the client an EndpointResolver that finds it."
        }
        if (targets.size == 1) return targets
        val first = Math.floorMod(turns.computeIfAbsent(context) { AtomicInteger() }.getAndIncrement(), targets.size)
        return targets.drop(first) + targets.take(first)
    }

    /** The next target of each context, so the requests rotate through its targets. */
    private val turns = ConcurrentHashMap<String, AtomicInteger>()

    private suspend fun build(request: PeerRequest, base: String, accept: String): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(base + request.path))
            .timeout((request.timeout ?: timeoutOf(request.context)).toJavaDuration())
            .header(ACCEPT, accept)
        when (val mode = mode) {
            is ClientMode.Service -> {
                mode.service?.let { builder.header(WowHeaders.SERVICE, it) }
                request.call?.let { builder.header(WowHeaders.CALL, CallWires.encode(it)) }
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
            builder.header(
                CONTENT_TYPE,
                JSON
            ).method(request.method, HttpRequest.BodyPublishers.ofByteArray(body)).build()
        }
    }

    companion object {
        const val JSON: String = "application/json"
        const val PROBLEM_JSON: String = "application/problem+json"
        const val EVENT_STREAM: String = "text/event-stream"
        const val JSON_ACCEPT: String = "$JSON, $PROBLEM_JSON"
        const val CONTENT_TYPE: String = "Content-Type"
        const val ACCEPT: String = "Accept"
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
