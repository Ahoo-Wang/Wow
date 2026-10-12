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

import me.ahoo.wow.api.result.WaitSignal
import java.net.URI
import java.net.http.HttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The internal port (decisions D89, D109): the cluster channel and the wait signal entry of a node, served apart from
 * its public port, over HTTP/1.1 and h2c, and trusted without authentication (D35).
 */
object InternalHttp {
    /** Takes a [me.ahoo.wow.runtime.cluster.ForwardCommand] and answers its [me.ahoo.wow.runtime.cluster.ForwardReply]. */
    const val FORWARD_PATH: String = "/wow/internal/forward"

    /** Takes a [WaitSignal] for a wait started on this node; answers 204. */
    const val SIGNALS_PATH: String = "/wow/internal/signals"

    const val JSON: String = "application/json"

    /**
     * The largest body the first request of a connection may carry for the server to upgrade it to h2c: the host
     * sets its internal server's `h2cMaxContentLength` to it. The JDK client upgrades with whatever request opens a
     * connection, here a `POST` with its body, and a server that takes no body on an upgrade request (Reactor Netty's
     * default) answers it 413. It is the default in-memory limit of the WebFlux codecs, which bound every later
     * request the same way; a larger message fails, and its forward runs locally (decision D78).
     */
    const val MAX_UPGRADE_BODY: Int = 256 * 1024

    /** The default connect timeout of [client]: a peer that does not accept a connection by then is unreachable. */
    val DEFAULT_CONNECT_TIMEOUT: Duration = 1.seconds

    /**
     * A JDK [HttpClient] for the internal port: HTTP/2, which over cleartext it reaches by upgrading the first request
     * of a connection from HTTP/1.1 (h2c), then multiplexes on that connection. One client keeps one connection per
     * peer, so a node shares it between its [HttpClusterChannel] and its [HttpSignalSender].
     */
    fun client(connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT): HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(connectTimeout.toJavaDuration())
        .build()

    /** The URI of [path] on the internal port at [address] (`host:port`). */
    fun uri(address: String, path: String): URI = URI.create("http://$address$path")
}
