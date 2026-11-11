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
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.cluster.ClusterChannelException
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.ClusterStream
import me.ahoo.wow.runtime.cluster.ForwardCommand
import me.ahoo.wow.runtime.cluster.ForwardReply
import tools.jackson.core.JacksonException
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The [ClusterChannel] over HTTP: a forward is a `POST` of its JSON to
 * [InternalHttp.FORWARD_PATH] on the peer's internal port, through the JDK [client] (HTTP/2 over h2c).
 *
 * The client's first request to a peer opens the connection and upgrades it to h2c, which on a node that started a
 * moment ago takes longer than a forward's timeout. [connect] opens it with a request of its own, a
 * `HEAD` of [InternalHttp.FORWARD_PATH] that the peer answers without running anything (with any status), bounded by
 * [connectTimeout]: concurrent callers share one, and a peer once connected is not asked again until a forward to it
 * fails for want of a connection. A peer that cannot be reached in time is a [ClusterChannelException].
 *
 * A peer's connection carries at most [InternalHttp.MAX_CONCURRENT_STREAMS] requests at a time, which the JDK client
 * does not queue past. The channel keeps at most [streams] forwards open to a peer, leaving the rest of the peer's
 * streams to the node's [HttpSignalSender]. A forward [reserves][reserve] one of them before it is sent, waiting for
 * one, in order, with no bound of its own: the router bounds the wait by the forward's deadline, and a forward that
 * got none by then was not sent, which the router answers as such (withdrawn or busy), never by running it locally,
 * which near the deadline would put a second writer beside the owner. One the JDK still refuses for the peer's limit,
 * a stream just ended that it has not released yet, is sent again on its stream after a short pause, until the
 * deadline, and is then [unsent][ClusterChannelException.unsent] too.
 *
 * The caller bounds a forward ([me.ahoo.wow.runtime.cluster.PartitionedRouter]), and the channel stops waiting for the
 * reply [answerGrace] after the forward's deadline too, so a forward never outlives that. Its stream stays open,
 * counted among [streams], until the owner's answer ends it, which comes about the deadline (the owner withdraws a
 * command still queued at its wait's deadline), and the router waits for that answer as long. Only an owner that has
 * not answered [answerGrace] after the deadline has the stream reset: resetting each stream at the very deadline it is
 * answered at made the peer's server close the whole connection under load, failing every forward on it
 * ([InternalHttp.ANSWER_GRACE]).
 *
 * Whatever keeps a reply from arriving (no connection, no reply by the deadline and its grace, a reset stream or a
 * closed connection, a status other than 200, a body that is not a reply) is a [ClusterChannelException]. Only one
 * raised before the request was sent (no connection to the peer, refused or not opened in time, or refused by the JDK
 * for the peer's limit until the deadline) is [unsent][ClusterChannelException.unsent], which the router degrades to
 * running the command locally while the forward's deadline is ahead; nothing after the send is, whose outcome is
 * unknown.
 *
 * Each forward names the node's [service] in `Wow-Service`. A peer that answers 401 or 403 did not
 * take the forward, being a node of another service: a refusal, which is [unsent][ClusterChannelException.unsent] too,
 * and logged as a warning, as the nodes are configured apart.
 */
@InternalWowApi
class HttpClusterChannel(
    private val client: HttpClient = InternalHttp.client(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    private val service: String? = null,
    /** The forwards open to one peer at a time: its connection's streams, less the signal sender's. */
    private val streams: Int = DEFAULT_STREAMS,
    /** How long a forward's stream stays open past its deadline for the owner's answer before it is reset. */
    override val answerGrace: Duration = InternalHttp.ANSWER_GRACE,
) : ClusterChannel {
    /** The connections opened or opening, by peer address: completed once the peer answered. */
    private val connections = ConcurrentHashMap<String, CompletableFuture<*>>()

    /** The streams free to each peer, by address: one is taken until the forward's response ends its stream. */
    private val freeStreams = ConcurrentHashMap<String, Semaphore>()

    init {
        require(streams >= 1) { "A forward takes a stream." }
        require(!answerGrace.isNegative()) { "The answer grace is never negative." }
    }

    override suspend fun connect(address: String) {
        val opening = connections.computeIfAbsent(address, ::open)
        try {
            // A copy: a caller that gives up does not cancel the connection the others wait for.
            opening.copy().await()
        } catch (failure: IOException) {
            connections.remove(address, opening)
            throw ClusterChannelException("Connecting to $address failed: $failure", failure, unsent = true)
        }
    }

    private fun open(address: String): CompletableFuture<*> {
        val request = HttpRequest.newBuilder(InternalHttp.uri(address, InternalHttp.FORWARD_PATH))
            .timeout(connectTimeout.toJavaDuration())
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionallyCompose { failure ->
            // A peer whose streams are all taken is connected: the forward waits for one of them.
            val cause = (failure as? CompletionException)?.cause ?: failure
            if (cause.isStreamLimit()) {
                CompletableFuture.completedFuture(null)
            } else {
                CompletableFuture.failedFuture(cause)
            }
        }
    }

    /**
     * Takes one of the [streams] to [address], waiting for one, in order, with no bound of its own: the router's bound
     * ends the wait at the forward's deadline, before anything is sent.
     */
    override suspend fun reserve(address: String): ClusterStream {
        val free = freeStreams.computeIfAbsent(address) { Semaphore(streams) }
        free.acquire()
        return Reserved(address, free)
    }

    override suspend fun forward(address: String, request: ForwardCommand): ForwardReply {
        val stream = reserve(address)
        try {
            return stream.forward(request)
        } finally {
            stream.release()
        }
    }

    /** One of the [streams] to [address], taken from [free]: freed once, by [release] or by the forward sent on it. */
    private inner class Reserved(private val address: String, private val free: Semaphore) : ClusterStream {
        private val unused = AtomicBoolean(true)

        override suspend fun forward(request: ForwardCommand): ForwardReply {
            // Encoded while the stream is still the caller's to release, should the request not encode.
            val body = ClusterCodec.encode(request)
            check(unused.compareAndSet(true, false)) { "A stream carries one forward." }
            return post(address, free, request.deadline, body)
        }

        override fun release() {
            if (unused.compareAndSet(true, false)) {
                free.release()
            }
        }
    }

    /** Posts the forward [body] due by [deadline] to [address] on the stream taken from [free]: the peer's reply. */
    @Suppress("ThrowsCount") // Each way a reply fails to arrive, as its own ClusterChannelException.
    private suspend fun post(address: String, free: Semaphore, deadline: Long, body: ByteArray): ForwardReply {
        val response = try {
            exchange(address, free, deadline) { remaining ->
                HttpRequest.newBuilder(InternalHttp.uri(address, InternalHttp.FORWARD_PATH))
                    .timeout((remaining + answerGrace).toJavaDuration())
                    .header("Content-Type", InternalHttp.JSON)
                    .apply { service?.let { header(WowHeaders.SERVICE, it) } }
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build()
            }
        } catch (unconnected: HttpConnectTimeoutException) {
            // No connection was opened in time: the request was not sent.
            connections.remove(address)
            throw ClusterChannelException("Forwarding to $address failed: $unconnected", unconnected, unsent = true)
        } catch (timeout: HttpTimeoutException) {
            throw ClusterChannelException("Forwarding to $address failed: $timeout", timeout)
        } catch (refused: ConnectException) {
            // No connection could be opened: the request was not sent.
            connections.remove(address)
            throw ClusterChannelException("Forwarding to $address failed: $refused", refused, unsent = true)
        } catch (failure: IOException) {
            // The connection may be gone: the next forward opens one first. The request may have reached the peer.
            connections.remove(address)
            throw ClusterChannelException("Forwarding to $address failed: $failure", failure)
        }
        if (response.statusCode() in REFUSED) {
            // The peer refused the forward before reading it, as another service's node: it surely did not run it.
            logger.warn {
                "$address refused a forward of service $service (HTTP ${response.statusCode()}): it is no node of it."
            }
            throw ClusterChannelException(
                "$address refused the forward of service $service with HTTP ${response.statusCode()}.",
                unsent = true,
            )
        }
        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            throw ClusterChannelException("$address answered the forward with HTTP ${response.statusCode()}.")
        }
        return try {
            ClusterCodec.decodeReply(response.body())
        } catch (malformed: JacksonException) {
            throw ClusterChannelException("$address answered the forward with a malformed reply.", malformed)
        }
    }

    /**
     * [address]'s response to the request [build] makes for the time left until [deadline], on the stream taken from
     * [free], waited for until [deadline] and the [answerGrace] after it: an [HttpTimeoutException] when none came by
     * then. The stream stays taken until the response, or the request's own timeout, ends it. A request the JDK refuses
     * for the peer's limit (a stream that just ended it has not released yet) is sent again on the same stream after a
     * short pause, until [deadline]: then it is [unsent][ClusterChannelException.unsent], as it never was sent.
     */
    private suspend fun exchange(
        address: String,
        free: Semaphore,
        deadline: Long,
        build: (remaining: Duration) -> HttpRequest,
    ): HttpResponse<ByteArray> {
        // The last request given to the JDK, which frees the stream once it ends; a refused one has ended.
        var sending: CompletableFuture<HttpResponse<ByteArray>>? = null
        try {
            var pause = MIN_STREAM_PAUSE
            while (true) {
                if (deadline <= clock()) {
                    throw ClusterChannelException(
                        "The streams to $address stayed taken until the forward's deadline; it was not sent.",
                        unsent = true,
                    )
                }
                val attempt = client.sendAsync(build(remaining(deadline)), HttpResponse.BodyHandlers.ofByteArray())
                sending = attempt
                responseTo(attempt, deadline)?.let { return it }
                // Not sent: the peer's streams were all taken, one of them only just ended. Again after a pause.
                logger.debug { "The streams to $address are all taken; the forward waits for one." }
                delay(pause)
                pause = minOf(pause * 2, MAX_STREAM_PAUSE)
            }
        } finally {
            val last = sending
            if (last == null) free.release() else last.whenComplete { _, _ -> free.release() }
        }
    }

    /**
     * The response [attempt] gets, waited for until [deadline] and the [answerGrace] after it; null when the JDK refused
     * it for the peer's limit, unsent.
     */
    private suspend fun responseTo(
        attempt: CompletableFuture<HttpResponse<ByteArray>>,
        deadline: Long
    ): HttpResponse<ByteArray>? =
        try {
            // A copy: cancelling the JDK's own future completes it, which would free a stream still open.
            withTimeoutOrNull(remaining(deadline) + answerGrace) { attempt.copy().await() }
                ?: throw HttpTimeoutException("No reply by the forward's deadline and its grace.")
        } catch (failure: IOException) {
            if (failure.isStreamLimit()) null else throw failure
        }

    /** The time left until [deadline], at least a millisecond. */
    private fun remaining(deadline: Long): Duration = (deadline - clock()).coerceAtLeast(1).milliseconds

    @InternalWowApi
    companion object {
        /**
         * The default of the forwards open to one peer at a time: the peer's [InternalHttp.MAX_CONCURRENT_STREAMS] less
         * the [HttpSignalSenderOptions.DEFAULT_CONCURRENCY] the node's signal sender takes on the same connection.
         */
        const val DEFAULT_STREAMS: Int =
            InternalHttp.MAX_CONCURRENT_STREAMS - HttpSignalSenderOptions.DEFAULT_CONCURRENCY

        private val MIN_STREAM_PAUSE = 1.milliseconds
        private val MAX_STREAM_PAUSE = 50.milliseconds

        /**
         * How long [connect] waits for a peer to answer on a new connection: well over the second a forward waits,
         * as a node's first connection to a peer that has just started pays for the h2c upgrade and the peer's warm-up.
         */
        val DEFAULT_CONNECT_TIMEOUT: Duration = 5.seconds

        private val REFUSED = setOf(HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN)
        private val logger = KotlinLogging.logger {}
    }
}
