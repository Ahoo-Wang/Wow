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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.checkUse
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.result.FrameworkCodes
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
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Flow.Publisher
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
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

/**
 * The lookup of [context]'s [port] listed no node, though the resolver reaches the context: nothing was sent. Its
 * message is the detail of the `wow.peer.unavailable` it ends as.
 */
internal class NoNodeListed(context: String, port: Port) :
    IOException("The lookup of $context lists no node on its ${port.name.lowercase()} port now.")

/** What a peer answered: a 2xx response's [Ok], or a [Problem]. */
internal sealed interface Answer<out T> {
    class Ok<T>(val value: T) : Answer<T>

    class Problem(val problem: PeerProblem) : Answer<Nothing>
}

/**
 * The kind of a request, as the transport treats it: the problems it sends it again for ([retries]), and the catalog
 * code of one it sent and got no answer to: a command's outcome is then unknown (`wow.command.unknown`, sent again with
 * its request id), a query's repeats nothing (`wow.peer.unavailable`, or `wow.query.timeout` once its timeout passed).
 * A request no node of the peer took, never sent, is `wow.peer.unavailable` whatever its kind.
 */
internal enum class Exchange(val retries: (PeerProblem) -> Boolean) {
    /** A command of a [CommandClient][me.ahoo.wow.api.client.CommandClient], sent again with its request id. */
    COMMAND(PeerProblem::retriesCommand),

    /** A query, or a read of a peer's Manifest. */
    QUERY(PeerProblem::retriesQuery),

    /** A saga's synchronous call, never sent again here: its subscription's retries are the ones that count. */
    CALL({ false });

    /** The problem of [request], sent and [failure] before it was answered. */
    fun unanswered(request: PeerRequest, failure: IOException): PeerProblem = when {
        this != QUERY -> PeerProblem.of(
            FrameworkCodes.COMMAND_UNKNOWN,
            "The command sent to ${request.context} was not answered (${failure.javaClass.name}): whether it committed is unknown.",
            failure,
        )
        failure is HttpTimeoutException -> PeerProblem.of(
            FrameworkCodes.QUERY_TIMEOUT,
            "The query sent to ${request.context} was not answered within its timeout.",
            failure,
        )
        else -> PeerProblem.of(
            FrameworkCodes.PEER_UNAVAILABLE,
            "The connection to ${request.context} was lost before it answered (${failure.javaClass.name}).",
            failure,
        )
    }
}

/**
 * The HTTP exchanges of the clients with the peers, on the JDK [client]: each request goes to the base
 * URL [endpoints] gives its context on the [mode]'s port, asked again for every attempt; it carries, in service mode,
 * the service's name (`Wow-Service`) and the root context (`Wow-Call`), in user mode the end user's
 * forwarded credential headers, and the trace the coroutine runs in; it is answered within its
 * context's timeout of [timeouts], else [timeout] (the response's head, for a stream), and retried as [retry] says.
 * A failure of its own (no node reached, a connection lost, no answer in time) it answers as a problem of the error
 * catalog, as its [Exchange] says, the failure its cause: a caller sees one kind of failure, whatever met it.
 */
internal class HttpTransport(
    val endpoints: EndpointResolver,
    val mode: ClientMode,
    val client: HttpClient,
    val timeout: Duration,
    val retry: RetryPolicy,
    val timeouts: Map<String, Duration> = emptyMap(),
    /** The nodes that took a request and did not answer it, which the requests that follow pass over. */
    val silent: SilentPeers = SilentPeers(),
) {
    init {
        require(timeout.isPositive()) { "A request's timeout is positive." }
        require(timeouts.values.all { it.isPositive() }) { "A request's timeout is positive: $timeouts." }
    }

    /** How long a request to [context] may take until its peer answers it. */
    fun timeoutOf(context: String): Duration = timeouts[context] ?: timeout

    /** How long [request] may take until its peer answered it whole: its own timeout, else its context's. */
    private fun boundOf(request: PeerRequest): Duration = request.timeout ?: timeoutOf(request.context)

    /**
     * [request] answered as bytes, retried as its [kind] says of its problem. Its whole answer, the body included, is
     * bounded by the request's timeout ([boundOf]): the JDK's own ends once the response's head is in (#6807).
     */
    suspend fun exchange(request: PeerRequest, kind: Exchange): Answer<ByteArray> =
        withRetries(request, kind) { base ->
            val http = build(request, base, JSON_ACCEPT)
            val head = HeadMark(HttpResponse.BodyHandlers.ofByteArray())
            val response = within(request, boundOf(request), head, { client.sendAsync(http, head) }) { it }
            val status = response.statusCode()
            if (status in SUCCESS) {
                Answer.Ok(response.body())
            } else {
                Answer.Problem(Problems.read(status, response.headers(), response.body()))
            }
        }

    /**
     * [request] answered as a `text/event-stream`, its lines once the response's head says it is one, retried as its
     * [kind] says of its problem; a 2xx response of another type (a peer that answered with one JSON document)
     * is its body read whole, as the data of one event. The head and a body read whole are bounded as [exchange]'s
     * answer is; the lines are bounded by what is left of the request's own timeout, a command's wait and its grace,
     * when it has one ([PeerRequest.timeout]): past it their flow fails with an [HttpTimeoutException] (#6807). A
     * request without one, a query's streamed list, which resumes from its position when its connection is lost,
     * streams for as long as its peer sends.
     */
    suspend fun stream(request: PeerRequest, kind: Exchange): Answer<Flow<String>> =
        withRetries(request, kind) { base ->
            val sent = TimeSource.Monotonic.markNow()
            val bound = boundOf(request)
            val http = build(request, base, EVENT_STREAM)
            val head = HeadMark(HttpResponse.BodyHandlers.ofPublisher())
            within(request, bound, head, { client.sendAsync(http, head) }) { response ->
                val status = response.statusCode()
                val body = response.body()
                when {
                    status !in SUCCESS -> Answer.Problem(Problems.read(status, response.headers(), body.bytes()))
                    response.headers().firstValue(CONTENT_TYPE).orElse("").startsWith(EVENT_STREAM) -> Answer.Ok(
                        request.timeout?.let { body.lines().within(request, sent, it) } ?: body.lines(),
                    )
                    else -> Answer.Ok(asEvent(body.bytes().decodeToString()))
                }
            }
        }

    /**
     * [answer] of one attempt's exchange with a target, which [send] starts, within [bound]: past it, an
     * [HttpTimeoutException], the request sent and not answered within its bound. Its end, or its cancellation,
     * cancels the exchange if it is still open, which closes its connection.
     *
     * **Until the response's head, the JDK's verdict decides** (#6993). The JDK's own request timeout, which [build]
     * sets to the same bound, says whether the connection was made: an [HttpConnectTimeoutException] when it was not.
     * Both timers are due at once, and either may fire first, as on a loaded machine. So when the bound passes before
     * the head ([HeadMark]), the attempt waits for the JDK's verdict at most [VERDICT_GRACE]: a connection not made is
     * thrown as that, unsent, and the request fails over as a refused one does; any other verdict, or none within
     * the grace, is the request sent and not answered. Once the head is in, the JDK's timer no longer runs, and the
     * bound alone ends a slow body.
     */
    private suspend fun <B, T : Any> within(
        request: PeerRequest,
        bound: Duration,
        head: HeadMark<B>,
        send: () -> CompletableFuture<HttpResponse<B>>,
        answer: suspend (HttpResponse<B>) -> T,
    ): T {
        val exchange = send()
        try {
            // A copy is awaited, so the bound's cancellation leaves the exchange to give its own verdict.
            withTimeoutOrNull(bound) { outcomeOf { answer(exchange.copy().await()) } }?.let { return it.getOrThrow() }
            if (!head.arrived) {
                val verdict = withTimeoutOrNull(VERDICT_GRACE) { outcomeOf { exchange.copy().await() } }
                (verdict?.exceptionOrNull() as? HttpConnectTimeoutException)?.let { throw it }
            }
            throw unansweredWithin(request, bound)
        } finally {
            exchange.cancel(true)
        }
    }

    /**
     * The handler of one attempt's response, [handler]'s, marking when its head arrived ([arrived]): the JDK's own
     * request timeout no longer runs after it. One per attempt: with the copy of the JDK's future the attempt awaits
     * within its bound, one of the two small objects [within] adds to every attempt, and one more copy for the verdict
     * when the bound passes before the head.
     */
    private class HeadMark<T>(private val handler: HttpResponse.BodyHandler<T>) : HttpResponse.BodyHandler<T> {
        @Volatile
        var arrived = false
            private set

        override fun apply(info: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<T> {
            arrived = true
            return handler.apply(info)
        }
    }

    /** These lines, failing with an [HttpTimeoutException] once [bound] passed since [sent]. */
    private fun Flow<String>.within(request: PeerRequest, sent: TimeMark, bound: Duration): Flow<String> = flow {
        val outcome = withTimeoutOrNull(bound - sent.elapsedNow()) { outcomeOf { collect { emit(it) } } }
        (outcome ?: throw unansweredWithin(request, bound)).getOrThrow()
    }

    /**
     * What [block] returned or threw, its cancellation aside: a failure carried out of a timeout's scope as a value is
     * rethrown as itself, where one thrown across it is a copy, made to recover the coroutine's stack trace, and a
     * problem's cause is the failure itself.
     */
    @Suppress("TooGenericExceptionCaught") // Every failure is rethrown, as itself, once out of the scope.
    private inline fun <T> outcomeOf(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private fun unansweredWithin(request: PeerRequest, bound: Duration) =
        HttpTimeoutException("The answer of ${request.context} did not end within its bound of $bound.")

    /**
     * [attempt] at [request]'s targets, retried as [retry] says: each attempt goes through the targets of its context
     * ([EndpointResolver.resolveAll]) from the next one in turn, and fails over to the next target on a connection
     * that was not made or on a node that refused before handling ([PeerProblem.refusedBeforeHandling]), each target at
     * most once, without spending a retry. When every target failed to connect, the resolver hears it
     * ([EndpointResolver.unreachable]); so it does when the lookup lists no target ([NoNodeListed]). Either way the
     * request was not sent, and is retried. What the last target answered is then retried while [kind] says so, and
     * so is a request sent and not answered, as the problem its [kind] gives it (a command again with its request id;
     * a saga's call never here). A request no attempt could send is `wow.peer.unavailable`. One an attempt sent and
     * got no answer to, a command's outcome unknown, ends as that attempt's problem when the attempts after it send
     * nothing or are answered only that they were not taken ([endOf]), so it stays unknown, retried with its request
     * id, never a new request that could commit it twice (#6806).
     *
     * A request a node took and did not answer within its bound (the node went silent, [SilentPeers.silenced]) is never
     * sent to that node again. It is sent again under the same request id at most once, and only to another live node
     * of its context, one no request found silent ([targetsOf]), which forwards a command to its partition's owner, so
     * the command's outcome is settled as a duplicate or a commit; what that attempt meets is the request's end, as
     * [endOf] says. With no other live node it fails once, as the problem its [kind] gives it (#6809).
     */
    private suspend fun <T> withRetries(
        request: PeerRequest,
        kind: Exchange,
        attempt: suspend (base: String) -> Answer<T>,
    ): Answer<T> {
        var attempts = 1
        // The problem of the last attempt that sent the request and got no answer, kept over unsent attempts after it.
        var unanswered: Answer.Problem? = null
        // The nodes that took the request and went silent on it: the attempt after the first is the request's last.
        val silentOnIt = HashSet<String>()
        while (true) {
            val last = silentOnIt.isNotEmpty()
            val targets = targetsOf(request.context, except = silentOnIt)
            // No other live node: the request is not sent again to the silent one.
            if (last && targets.isEmpty()) return checkNotNull(unanswered)
            val answer = try {
                failover(request, targets, silentOnIt, attempt)
            } catch (failure: IOException) {
                if (failure.unsent()) {
                    // A connection that was not made sent nothing: refused, or not answered in time.
                    val wait = waitAfter(attempts, last, null)
                        ?: return unanswered ?: unreached(request, failure)
                    delay(wait)
                    attempts++
                    continue
                }
                // Sent and not answered: the problem its kind says, retried as an answered one is.
                Answer.Problem(kind.unanswered(request, failure)).also { unanswered = it }
            }
            if (answer is Answer.Problem && kind.retries(answer.problem)) {
                val wait = waitAfter(attempts, last, answer.problem.retryAfter) ?: return endOf(answer, unanswered)
                delay(wait)
                attempts++
                continue
            }
            return endOf(answer, unanswered)
        }
    }

    /**
     * What a request whose last attempt [answer]ed ends as: that answer, unless an attempt before it was sent and not
     * answered ([unanswered]), its outcome one a retry with the same request id settles, and [answer] says only that
     * its attempt was not taken ([PeerProblem.notTaken]), which is no word on the earlier attempt: then that attempt's
     * problem, the command's outcome still unknown (#6806). A result, a refusal of the command itself, or a problem a
     * retry with the same request id may get past is an answer about the request, and ends it as it is.
     */
    private fun <T> endOf(answer: Answer<T>, unanswered: Answer.Problem?): Answer<T> = when {
        unanswered == null || unanswered.problem.retry != Retry.SAME_REQUEST -> answer
        answer is Answer.Problem && answer.problem.notTaken -> unanswered
        else -> answer
    }

    /**
     * How long to wait before the attempt after [attempts], [retryAfter] the peer asked for, as [retry] says; null when
     * no attempt is left: the policy's spent, or the attempt was the [last] a request a node went silent on makes.
     */
    private fun waitAfter(attempts: Int, last: Boolean, retryAfter: Duration?): Duration? =
        if (last) null else retry.delayBefore(attempts, retryAfter)

    /** The problem of [request], which no attempt could send, the last meeting [failure]: `wow.peer.unavailable`. */
    private fun unreached(request: PeerRequest, failure: IOException): Answer.Problem = Answer.Problem(
        PeerProblem.of(
            FrameworkCodes.PEER_UNAVAILABLE,
            (failure as? NoNodeListed)?.message
                ?: "No node of ${request.context} could be reached (${failure.javaClass.name}).",
            failure,
        ),
    )

    /**
     * One attempt at [request]'s [targets], in turn, failing over as [withRetries] says. A target that took the request
     * and did not answer it within its bound is bypassed by the requests that follow ([SilentPeers.silenced]) and joins
     * [silentOnIt]; one that answered, whatever it answered, is not bypassed ([SilentPeers.answered]).
     */
    private suspend fun <T> failover(
        request: PeerRequest,
        targets: List<String>,
        silentOnIt: MutableSet<String>,
        attempt: suspend (base: String) -> Answer<T>,
    ): Answer<T> {
        var unsent: IOException? = null
        var refused: Answer<T>? = null
        for (base in targets) {
            val answer = try {
                attempt(base)
            } catch (failure: IOException) {
                if (!failure.unsent()) throw unanswered(request.context, base, failure, silentOnIt)
                unsent = failure
                continue
            }
            silent.answered(request.context, mode.port, base)
            if (answer is Answer.Problem && answer.problem.refusedBeforeHandling) {
                refused = answer
                continue
            }
            return answer
        }
        refused?.let { return it }
        endpoints.unreachable(request.context, mode.port)
        throw unsent ?: NoNodeListed(request.context, mode.port)
    }

    /**
     * [failure], of a request sent to [base] of [context] and not answered; a timeout, the request's bound passed
     * without an answer, has the requests that follow pass [base] over ([SilentPeers.silenced]), and puts it in
     * [silentOnIt], the request's own.
     */
    private fun unanswered(
        context: String,
        base: String,
        failure: IOException,
        silentOnIt: MutableSet<String>,
    ): IOException {
        if (failure is HttpTimeoutException) {
            silent.silenced(context, mode.port, base)
            silentOnIt += base
        }
        return failure
    }

    /**
     * The targets of [context], each a base URL without its trailing slash, from the next one in turn, those
     * [silent] bypasses left out unless all are; none while the lookup lists no node of a context the resolver
     * reaches, such as a service whose instances are all down for a moment. A context it does not reach has no URL at
     * all: the code's mistake, not an outage. With [except], the nodes a request found silent, it is the live nodes
     * alone: those and every bypassed one left out, none when no other is left.
     */
    internal suspend fun targetsOf(context: String, except: Set<String> = emptySet()): List<String> {
        val listed = endpoints.resolveAll(context, mode.port)
        val chosen = silent.choose(
            context,
            mode.port,
            listed.map { it.toString().trimEnd('/') },
            (listed as? PeerList)?.generation,
            all = except.isEmpty(),
        )
        val targets = if (except.isEmpty()) chosen else chosen.filter { it !in except }
        if (targets.isEmpty()) {
            if (except.isNotEmpty()) return targets
            checkUse(endpoints.reaches(context, mode.port)) {
                "No URL calls context $context on its ${mode.port.name.lowercase()} port: set " +
                    "wow.peers.$context.${if (mode.port == Port.PUBLIC) "public-url" else "url"}, " +
                    "or give the client an EndpointResolver that finds it."
            }
            return targets
        }
        if (targets.size == 1) return targets
        val first = Math.floorMod(turns.computeIfAbsent(context) { AtomicInteger() }.getAndIncrement(), targets.size)
        return targets.drop(first) + targets.take(first)
    }

    /** The next target of each context, so the requests rotate through its targets. */
    private val turns = ConcurrentHashMap<String, AtomicInteger>()

    private suspend fun build(request: PeerRequest, base: String, accept: String): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(base + request.path))
            .timeout(boundOf(request).toJavaDuration())
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

        /**
         * How long an attempt whose bound passed before its response's head waits for the JDK's own verdict, due at
         * the same instant: whether its connection was made ([within]). Only a timer late by load spends it.
         */
        internal val VERDICT_GRACE: Duration = 1.seconds
        const val CONTENT_TYPE: String = "Content-Type"
        const val ACCEPT: String = "Accept"
        private val SUCCESS = 200..299

        /**
         * Whether this failure left the request unsent: a connection that was never made, or a lookup that listed no
         * node to connect to.
         */
        internal fun IOException.unsent(): Boolean = when (this) {
            is ConnectException, is HttpConnectTimeoutException, is NoNodeListed -> true
            else -> false
        }

        /** A path segment, percent-encoded. */
        fun encode(segment: String): String = URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")

        /**
         * [document] as the lines of one server-sent event whose data it is, then an `end` event: a body read whole is
         * a whole answer, as a stream that ends with `end` is.
         */
        private fun asEvent(document: String): Flow<String> =
            (document.lines().map { "data: $it" } + "" + WHOLE).asFlow()

        /** The lines of the `end` event of a whole answer. */
        private val WHOLE = listOf("event: end", "data: {\"reason\":\"complete\"}", "")

        /** The whole body this publishes. */
        private suspend fun Publisher<List<ByteBuffer>>.bytes(): ByteArray =
            lines().fold(ByteArrayOutputStream()) { out, line ->
                if (out.size() > 0) out.write('\n'.code)
                out.write(line.toByteArray())
                out
            }.toByteArray()
    }
}
