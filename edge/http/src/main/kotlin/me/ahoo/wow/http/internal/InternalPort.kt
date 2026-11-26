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
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.http.problem.LingeringClose
import me.ahoo.wow.http.problem.ReadTimeouts
import me.ahoo.wow.http.problem.ServerRefusals
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.netty.DisposableServer
import reactor.netty.http.HttpProtocol
import reactor.netty.http.server.HttpServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The internal port of the node: a Reactor Netty server of its own, apart from the public
 * WebFlux server, serving its routes (the cluster channel, the wait signals, the services' calls and peer reads) over
 * HTTP/1.1 and h2c. Its codecs take messages of up to [InternalPortSettings.maxMessageSize] bytes, which a host derives
 * from the public port's codec limit ([InternalHttp.messageLimit]), and the server takes an upgrade
 * request of up to that size too, as the JDK client reaches HTTP/2 by upgrading the first request of a connection.
 * It takes [InternalHttp.MAX_CONCURRENT_STREAMS] streams on a connection at a time, the limit the node's senders keep
 * within. It keeps Netty's guard against rapid resets: a connection on which it reads more than 200 `RST_STREAM`
 * frames in 30 seconds is closed.
 *
 * A request whose body stalls, nothing of it read for [InternalPortSettings.readTimeout], is answered 408 and its
 * connection closed (over h2c, its stream), as on the public port ([ReadTimeouts]): the timeout holds from the request's
 * headers to its body's end, so neither a body that keeps arriving nor an answer, a wait's or a peer read's stream, is
 * cut. Its [stop] closes the
 * connections still open, a request's whose body stalls among them: the host stops it last, once the node's forwards are
 * drained, so nothing on it is waited for (#6958).
 *
 * The internal side is a trusted environment: the network keeps it apart, so building the port checks
 * no credential. An application reads where it is bound ([boundPort]); building and running it is the host's ([of]).
 */
class InternalPort private constructor(
    private val port: Int,
    private val host: String?,
    routes: RouterFunction<ServerResponse>,
    private val maxMessageSize: Int,
    private val readTimeout: Duration,
) {
    private val adapter = ReactorHttpHandlerAdapter(
        RouterFunctions.toHttpHandler(
            routes,
            HandlerStrategies.builder().codecs { it.defaultCodecs().maxInMemorySize(maxMessageSize) }.build(),
        ),
    )

    @Volatile
    private var server: DisposableServer? = null

    /** The connections the server accepted and has not closed. */
    private val connections = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)

    /** Whether the server is bound. */
    val isRunning: Boolean get() = server != null

    /** The port the server is bound at, the one the system chose for port 0; fails while it is not bound. */
    val boundPort: Int get() = checkNotNull(server) { "The internal server is not bound." }.port()

    /** Binds the server, at once. */
    @InternalWowApi
    @Synchronized
    fun start() {
        if (server != null) {
            return
        }
        val refusals = LingeringClose.of(ServerRefusals.of(HttpServer.create(), ServerRefusals.Limits.NETTY))
        var configured = ReadTimeouts.of(refusals, readTimeout)
            .port(port)
            .protocol(HttpProtocol.HTTP11, HttpProtocol.H2C)
            .http2Settings { it.maxConcurrentStreams(InternalHttp.MAX_CONCURRENT_STREAMS.toLong()) }
            .httpRequestDecoder { it.h2cMaxContentLength(maxMessageSize) }
            .doOnChannelInit { _, channel, _ -> connections.add(channel) }
            .handle(adapter)
        host?.let { configured = configured.host(it) }
        server = configured.bindNow().also { logger.info { "Wow internal port bound at ${it.address()}." } }
    }

    /** Unbinds the server. */
    @InternalWowApi
    @Synchronized
    fun stop() {
        server?.disposeNow()
        server = null
        // Disposing the server closes its own socket alone: the connections it accepted stay open until closed.
        connections.close().awaitUninterruptibly(CLOSE_TIMEOUT.inWholeMilliseconds)
        logger.info { "Wow internal port stopped." }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** How long [stop] waits for the connections still open to close. */
        private val CLOSE_TIMEOUT: Duration = 1.seconds

        /** The internal port of [settings], serving [routes]. */
        @InternalWowApi
        @JvmStatic
        fun of(settings: InternalPortSettings, routes: RouterFunction<ServerResponse>): InternalPort =
            InternalPort(settings.port, settings.bindHost, routes, settings.maxMessageSize, settings.readTimeout)
    }
}

/**
 * What an [InternalPort] is built of: the [port] it binds (0 for one the system chooses) on [bindHost], the
 * [maxMessageSize] of its codecs, and its [readTimeout], the longest a request's body may stall between two reads, which
 * a host sets from `wow.http.read-timeout`; positive.
 */
@InternalWowApi
class InternalPortSettings(
    val port: Int,
    val bindHost: String,
    val maxMessageSize: Int = InternalHttp.MAX_UPGRADE_BODY,
    val readTimeout: Duration = READ_TIMEOUT,
) {
    init {
        require(readTimeout.isPositive()) { "A read timeout is positive: $readTimeout" }
    }

    @InternalWowApi
    companion object {
        /** The read timeout of both ports unless the host sets one (`wow.http.read-timeout`): 30 seconds. */
        val READ_TIMEOUT: Duration = 30.seconds
    }
}
