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
import me.ahoo.wow.spi.config.configCheck
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.netty.DisposableServer
import reactor.netty.http.HttpProtocol
import reactor.netty.http.server.HttpServer
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The internal port of the node (decisions D89, D109): a Reactor Netty server of its own, apart from the public
 * WebFlux server, serving its routes (the cluster channel, the wait signals, the services' calls and peer reads) over
 * HTTP/1.1 and h2c. Its codecs take messages of up to [InternalPortSettings.maxMessageSize] bytes, which a host derives
 * from the public port's codec limit ([InternalHttp.messageLimit], review A3-9), and the server takes an upgrade
 * request of up to that size too, as the JDK client reaches HTTP/2 by upgrading the first request of a connection.
 *
 * Built only by [of], which holds the port's security floor (decision D226, reviews A3-1, R5.C-2), so every host
 * gets it, Spring Boot or not: its cluster routes take the service's cluster tokens, and a port without one is refused
 * unless it runs for development or binds the loopback interface alone. An application reads where it is bound
 * ([boundPort]); building and running it is the host's.
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

        /**
         * The internal port of [settings], serving the routes [routes] builds over its cluster tokens. It refuses to
         * be built, with a `WowConfigurationException` of `wow.internal.token`:
         *
         * - when a cluster token is also a service token: one secret would open both the cluster routes and the
         *   call routes;
         * - without a cluster token, unless [InternalPortSettings.development] names an active development profile
         *   or the port binds [InternalPortSettings.bindHost], a loopback address, alone: a cluster route that takes
         *   every request is open to whatever reaches the port, other services too. Such a port is logged.
         */
        @InternalWowApi
        @JvmStatic
        fun of(settings: InternalPortSettings, routes: (ClusterTokens) -> RouterFunction<ServerResponse>): InternalPort {
            val tokens = settings.clusterTokens.map(String::trim).filter(String::isNotEmpty)
            configCheck(tokens.none { it in settings.serviceTokens }, KEY, null, "Use another secret.") {
                "A cluster token (wow.internal.token) is also a service token of wow.http.services."
            }
            if (tokens.isEmpty()) {
                val bindHost = settings.bindHost
                configCheck(
                    settings.development.isNotEmpty() || isLoopback(bindHost),
                    KEY,
                    null,
                    "Set wow.internal.token. Only a profile of wow.internal.dev-profiles " +
                        "${settings.devProfiles}, or an internal port bound to the loopback interface " +
                        "alone, runs without a cluster token.",
                ) {
                    "wow.internal.token is not set: the internal port at $bindHost would take forwards and signals " +
                        "from anyone who reaches it."
                }
                logger.warn {
                    "No cluster token (wow.internal.token): the internal port at $bindHost takes every forward and " +
                        "signal, as for development (${settings.development.ifEmpty { listOf("loopback") }.joinToString()})."
                }
            }
            return InternalPort(settings.port, settings.bindHost, routes(ClusterTokens(tokens)), settings.maxMessageSize)
        }

        private const val KEY = "wow.internal.token"

        private fun isLoopback(host: String): Boolean = try {
            InetAddress.getByName(host).isLoopbackAddress
        } catch (_: UnknownHostException) {
            false
        }
    }
}

/**
 * What an [InternalPort] is built of: the [port] it binds (0 for one the system chooses) on [bindHost], the
 * [clusterTokens] its cluster routes take (`wow.internal.token`, the first the one the node sends), the
 * [serviceTokens] of the services' call routes, none of which may be a cluster token, the [development] profiles
 * active among [devProfiles] (`wow.internal.dev-profiles`), under which a port may run without a cluster token, and
 * the [maxMessageSize] of its codecs.
 */
@InternalWowApi
class InternalPortSettings(
    val port: Int,
    val bindHost: String,
    val clusterTokens: List<String> = emptyList(),
    val serviceTokens: Set<String> = emptySet(),
    val development: List<String> = emptyList(),
    val devProfiles: Collection<String> = emptyList(),
    val maxMessageSize: Int = InternalHttp.MAX_UPGRADE_BODY,
)
