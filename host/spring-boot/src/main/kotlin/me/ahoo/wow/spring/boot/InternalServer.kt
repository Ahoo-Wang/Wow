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

package me.ahoo.wow.spring.boot

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.http.internal.InternalHttp
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.netty.DisposableServer
import reactor.netty.http.HttpProtocol
import reactor.netty.http.server.HttpServer

/**
 * The internal port of the node (decisions D89, D109): a Reactor Netty server of its own, apart from the public
 * WebFlux server, serving [routes] (the cluster channel and the wait signals) over HTTP/1.1 and h2c. The JDK client
 * reaches HTTP/2 by upgrading the first request of a connection, a `POST` with its body, so the server takes an
 * upgrade request of up to [InternalHttp.MAX_UPGRADE_BODY] bytes, and keeps reading its HTTP/1.1 connections
 * ([KeepAliveReads]). It is not authenticated (D35): bind it to [host] where only the cluster reaches it.
 */
class InternalServer(
    private val port: Int,
    private val host: String?,
    routes: RouterFunction<ServerResponse>,
) {
    private val adapter = ReactorHttpHandlerAdapter(RouterFunctions.toHttpHandler(routes))

    @Volatile
    private var server: DisposableServer? = null

    /** Whether the server is bound. */
    val isRunning: Boolean get() = server != null

    /** The port the server is bound at, the one the system chose for port 0; fails while it is not bound. */
    val boundPort: Int get() = checkNotNull(server) { "The internal server is not bound." }.port()

    /** Binds the server, at once. */
    @Synchronized
    fun start() {
        if (server != null) {
            return
        }
        var configured = HttpServer.create()
            .port(port)
            .protocol(HttpProtocol.HTTP11, HttpProtocol.H2C)
            .httpRequestDecoder { it.h2cMaxContentLength(InternalHttp.MAX_UPGRADE_BODY) }
            .doOnChannelInit { _, channel, _ -> KeepAliveReads.install(channel) }
            .handle(adapter)
        host?.let { configured = configured.host(it) }
        server = configured.bindNow().also { logger.info { "Wow internal port bound at ${it.address()}." } }
    }

    /** Unbinds the server. */
    @Synchronized
    fun stop() {
        server?.disposeNow()
        server = null
        logger.info { "Wow internal port stopped." }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
