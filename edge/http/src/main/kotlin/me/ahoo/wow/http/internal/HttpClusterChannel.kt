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

import kotlinx.coroutines.future.await
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.cluster.ClusterChannelException
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.ForwardCommand
import me.ahoo.wow.runtime.cluster.ForwardReply
import tools.jackson.core.JacksonException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The [ClusterChannel] over HTTP (decisions D26, D89): a forward is a `POST` of its JSON to
 * [InternalHttp.FORWARD_PATH] on the peer's internal port, through the JDK [client] (HTTP/2 over h2c).
 *
 * The caller bounds a forward ([me.ahoo.wow.runtime.cluster.PartitionedRouter], decision D80) and cancelling it
 * aborts the request; the request also times out by itself at the forward's deadline, so a forward never outlives it.
 * Whatever keeps a reply from arriving (no connection, a timeout, a reset stream, a status other than 200, a body that
 * is not a reply) is a [ClusterChannelException], which the router degrades to running the command locally (D71,
 * D78).
 */
class HttpClusterChannel(
    private val client: HttpClient = InternalHttp.client(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ClusterChannel {
    override suspend fun forward(address: String, request: ForwardCommand): ForwardReply {
        val remaining = (request.deadline - clock()).coerceAtLeast(1)
        val http = HttpRequest.newBuilder(InternalHttp.uri(address, InternalHttp.FORWARD_PATH))
            .timeout(Duration.ofMillis(remaining))
            .header("Content-Type", InternalHttp.JSON)
            .POST(HttpRequest.BodyPublishers.ofByteArray(ClusterCodec.encode(request)))
            .build()
        val response = try {
            client.sendAsync(http, HttpResponse.BodyHandlers.ofByteArray()).await()
        } catch (failure: IOException) {
            throw ClusterChannelException("Forwarding to $address failed: $failure", failure)
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
}
