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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.WaitSignal
import java.net.URI
import java.net.http.HttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The internal port: the cluster channel and the wait signal entry of a node, served apart from
 * its public port, over HTTP/1.1 and h2c. A node names its service on each request (`Wow-Service`);
 * the port is reachable by the platform's own pods alone, a NetworkPolicy's boundary, not a token's.
 */
@InternalWowApi
object InternalHttp {
    /** Takes a [me.ahoo.wow.runtime.cluster.ForwardCommand] and answers its [me.ahoo.wow.runtime.cluster.ForwardReply]. */
    const val FORWARD_PATH: String = "/wow/internal/forward"

    /** Takes a [WaitSignal] for a wait started on this node; answers 204. */
    const val SIGNALS_PATH: String = "/wow/internal/signals"

    /** Whether the node is on standby, and when and by whom it was activated. */
    const val STANDBY_PATH: String = "/wow/node/standby"

    /** Activates a node started on standby, at a migration's cut-over: the operator's act, audited. */
    const val ACTIVATE_PATH: String = "/wow/node/activate"

    const val JSON: String = "application/json"

    /**
     * The default largest message of the internal port: the default in-memory limit of the WebFlux codecs. The host
     * sets its internal server's limits ([messageLimit]) from the public port's, so a command the public port takes is
     * taken when forwarded too; the server takes a body of up to that size on the request that upgrades a connection
     * to h2c as well (the channel opens each connection with a `HEAD`). A forward larger than the internal port takes
     * is refused ([me.ahoo.wow.runtime.cluster.Refusal.TOO_LARGE]), and its sender runs it.
     */
    const val MAX_UPGRADE_BODY: Int = 256 * 1024

    /** What a forward carries besides its command's body: ids, the ref, headers, the wait and the trace. */
    const val MESSAGE_OVERHEAD: Int = 64 * 1024

    /**
     * The largest message the internal port takes when the public port's codecs take bodies of up to [publicLimit]
     * bytes (a negative one: no limit): the body and a forward's [MESSAGE_OVERHEAD], never less than
     * [MAX_UPGRADE_BODY].
     */
    fun messageLimit(publicLimit: Int): Int {
        if (publicLimit < 0) {
            return Int.MAX_VALUE
        }
        val limit = maxOf(MAX_UPGRADE_BODY.toLong(), publicLimit.toLong() + MESSAGE_OVERHEAD)
        return limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

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
