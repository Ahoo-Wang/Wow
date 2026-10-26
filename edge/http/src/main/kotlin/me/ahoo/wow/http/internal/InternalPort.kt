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
import me.ahoo.wow.api.annotation.InternalWowApi
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.netty.DisposableServer
import reactor.netty.http.HttpProtocol
import reactor.netty.http.server.HttpServer

/**
 * The internal port of the node (decisions D89, D109): a Reactor Netty server of its own, apart from the public
 * WebFlux server, serving its routes (the cluster channel, the wait signals, the services' calls and peer reads) over
 * HTTP/1.1 and h2c. Its codecs take messages of up to [InternalPortSettings.maxMessageSize] bytes, which a host derives
 * from the public port's codec limit ([InternalHttp.messageLimit], review A3-9), and the server takes an upgrade
 * request of up to that size too, as the JDK client reaches HTTP/2 by upgrading the first request of a connection.
 *
 * The internal side is a trusted environment (decision D298): the network keeps it apart, so building the port checks
 * no credential. An application reads where it is bound ([boundPort]); building and running it is the host's ([of]).
 */
class InternalPort private constructor(
    private val port: Int,
    private val host: String?,
    routes: RouterFunction<ServerResponse>,
    private val maxMessageSize: Int,
) {
    private val adapter = ReactorHttpHandlerAdapter(
        RouterFunctions.toHttpHandler(
            routes,
            HandlerStrategies.builder().codecs { it.defaultCodecs().maxInMemorySize(maxMessageSize) }.build(),
        ),
    )

    @Volatile
    private var server: DisposableServer? = null

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
        var configured = HttpServer.create()
            .port(port)
            .protocol(HttpProtocol.HTTP11, HttpProtocol.H2C)
            .httpRequestDecoder { it.h2cMaxContentLength(maxMessageSize) }
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
        logger.info { "Wow internal port stopped." }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** The internal port of [settings], serving [routes]. */
        @InternalWowApi
        @JvmStatic
        fun of(settings: InternalPortSettings, routes: RouterFunction<ServerResponse>): InternalPort =
            InternalPort(settings.port, settings.bindHost, routes, settings.maxMessageSize)
    }
}

/**
 * What an [InternalPort] is built of: the [port] it binds (0 for one the system chooses) on [bindHost], and the
 * [maxMessageSize] of its codecs.
 */
@InternalWowApi
class InternalPortSettings(
    val port: Int,
    val bindHost: String,
    val maxMessageSize: Int = InternalHttp.MAX_UPGRADE_BODY,
)
