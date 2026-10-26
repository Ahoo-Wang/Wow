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
import kotlinx.coroutines.future.await
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.cluster.ClusterChannelException
import me.ahoo.wow.runtime.cluster.ClusterCodec
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The [ClusterChannel] over HTTP (decisions D26, D89): a forward is a `POST` of its JSON to
 * [InternalHttp.FORWARD_PATH] on the peer's internal port, through the JDK [client] (HTTP/2 over h2c).
 *
 * The client's first request to a peer opens the connection and upgrades it to h2c, which on a node that started a
 * moment ago takes longer than a forward's timeout (decision D80). [connect] opens it with a request of its own, a
 * `HEAD` of [InternalHttp.FORWARD_PATH] that the peer answers without running anything (with any status), bounded by
 * [connectTimeout]: concurrent callers share one, and a peer once connected is not asked again until a forward to it
 * fails for want of a connection. A peer that cannot be reached in time is a [ClusterChannelException].
 *
 * The caller bounds a forward ([me.ahoo.wow.runtime.cluster.PartitionedRouter], decision D80) and cancelling it
 * aborts the request; the request also times out by itself at the forward's deadline, so a forward never outlives it.
 * Whatever keeps a reply from arriving (no connection, a timeout, a reset stream, a status other than 200, a body that
 * is not a reply) is a [ClusterChannelException]. Only one raised before the request was sent (no connection to the
 * peer, refused or not opened in time) is [unsent][ClusterChannelException.unsent], which the router degrades to
 * running the command locally (D71); after the send, its outcome is unknown (decision D223).
 *
 * Each forward names the node's [service] in `Wow-Service` (decision D300). A peer that answers 401 or 403 did not
 * take the forward, being a node of another service: a refusal, which is [unsent][ClusterChannelException.unsent] too
 * (D223), and logged as a warning, as the nodes are configured apart.
 */
@InternalWowApi
class HttpClusterChannel(
    private val client: HttpClient = InternalHttp.client(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    private val service: String? = null,
) : ClusterChannel {
    /** The connections opened or opening, by peer address: completed once the peer answered. */
    private val connections = ConcurrentHashMap<String, CompletableFuture<*>>()

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
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
    }

    override suspend fun forward(address: String, request: ForwardCommand): ForwardReply {
        val remaining = (request.deadline - clock()).coerceAtLeast(1)
        val http = HttpRequest.newBuilder(InternalHttp.uri(address, InternalHttp.FORWARD_PATH))
            .timeout(remaining.milliseconds.toJavaDuration())
            .header("Content-Type", InternalHttp.JSON)
            .apply { service?.let { header(WowHeaders.SERVICE, it) } }
            .POST(HttpRequest.BodyPublishers.ofByteArray(ClusterCodec.encode(request)))
            .build()
        val response = try {
            client.sendAsync(http, HttpResponse.BodyHandlers.ofByteArray()).await()
        } catch (unconnected: HttpConnectTimeoutException) {
            // No connection was opened in time: the request was not sent (decision D223).
            connections.remove(address)
            throw ClusterChannelException("Forwarding to $address failed: $unconnected", unconnected, unsent = true)
        } catch (timeout: HttpTimeoutException) {
            throw ClusterChannelException("Forwarding to $address failed: $timeout", timeout)
        } catch (refused: ConnectException) {
            // No connection could be opened: the request was not sent (decision D223).
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

    @InternalWowApi
    companion object {
        /**
         * How long [connect] waits for a peer to answer on a new connection: well over the second a forward waits,
         * as a node's first connection to a peer that has just started pays for the h2c upgrade and the peer's warm-up.
         */
        val DEFAULT_CONNECT_TIMEOUT: Duration = 5.seconds

        private val REFUSED = setOf(HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN)
        private val logger = KotlinLogging.logger {}
    }
}
