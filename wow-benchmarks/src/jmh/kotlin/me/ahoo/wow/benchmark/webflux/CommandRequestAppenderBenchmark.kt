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

package me.ahoo.wow.benchmark.webflux

import com.sun.security.auth.UserPrincipal
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.scenario.CommandGatewayScenario
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.remoteIp
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.userAgent
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.command.CommandHandlerFunction
import me.ahoo.wow.webflux.route.command.DEFAULT_TIME_OUT
import me.ahoo.wow.webflux.route.command.appender.CommandRequestExtendHeaderAppender
import me.ahoo.wow.webflux.route.command.appender.CommandRequestHeaderAppender
import me.ahoo.wow.webflux.route.command.appender.CommandRequestRemoteIpHeaderAppender
import me.ahoo.wow.webflux.route.command.appender.CommandRequestUserAgentHeaderAppender
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandMessageExtractor
import me.ahoo.wow.webflux.route.policy.CommandWaitPolicy
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * The command HTTP path with the request header appenders the starter registers by default (remote IP, user agent,
 * extended `Command-Header-*` headers), which [CommandHandlerFunctionBenchmark] leaves out.
 *
 * Each operation builds its request, as a server does, so `appenders=none` and `appenders=default` differ only by the
 * appenders. [client] chooses where the remote IP comes from:
 * - `xff`: an `X-Forwarded-For` header (proxied traffic; no socket address is read);
 * - `loopback-reused`: no XFF; the socket address object is reused across requests, like requests on one keep-alive
 *   connection;
 * - `loopback-new`: no XFF; every request carries a fresh unresolved `127.0.0.1` address, like a new connection per
 *   request.
 * Up to 9.2.2 the appender read `getHostName()`, a reverse DNS lookup on the event loop: paid once per address object
 * (`loopback-reused`) or on every request (`loopback-new`, answered from the hosts file, the cheap case). Since 9.2.3
 * (E2) it reads the address literal and the three clients cost the same. `-p client=public-new` (a public client
 * address) measured the lookup against a real resolver; it stays available to compare older builds.
 *
 * Audit 9.3.0 D §F8 (design WP G2; E2 compares against this).
 */
@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class CommandRequestAppenderBenchmark {
    @Param("none", "default")
    lateinit var appenders: String

    @Param("xff", "loopback-reused", "loopback-new")
    lateinit var client: String

    private lateinit var gatewayScenario: CommandGatewayScenario
    private lateinit var extractor: DefaultCommandMessageExtractor
    private lateinit var handlerFunction: CommandHandlerFunction
    private lateinit var reusedAddress: InetSocketAddress
    private val failures = AtomicInteger()

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        val appenderList: List<CommandRequestHeaderAppender> = when (appenders) {
            "none" -> emptyList()
            "default" -> listOf(
                CommandRequestExtendHeaderAppender,
                CommandRequestUserAgentHeaderAppender,
                CommandRequestRemoteIpHeaderAppender,
            )

            else -> error("Unsupported appenders: $appenders")
        }
        require(client in setOf("xff", "loopback-reused", "loopback-new", "public-new")) {
            "Unsupported client: $client"
        }
        extractor = DefaultCommandMessageExtractor(
            SimpleCommandMessageFactory(NoOpValidator, SimpleCommandBuilderRewriterRegistry()),
            DefaultCommandBuilderExtractor,
            appenderList,
        )
        gatewayScenario = CommandGatewayScenario.create(subscribeToCart = false)
        handlerFunction = CommandHandlerFunction(
            aggregateRouteMetadata = WebFluxBenchmarkSupport.cartAggregateRouteMetadata,
            commandRouteMetadata = WebFluxBenchmarkSupport.addCartItemRouteMetadata,
            commandGateway = gatewayScenario.commandGateway,
            commandMessageExtractor = extractor,
            exceptionHandler = WebFluxRequestExceptionHandler(),
            commandWaitPolicy = CommandWaitPolicy(DEFAULT_TIME_OUT),
        )
        reusedAddress = unresolvedAddress(LOOPBACK)
        val probe = extract(request())
        if (appenders == "default") {
            check(probe.header.userAgent == USER_AGENT) { "User agent appender did not run." }
            check(probe.header["extended"] == "value") { "Extended header appender did not run." }
            val expectedIp = when (client) {
                "xff" -> CLIENT_IP
                "public-new" -> null
                // Since 9.2.3 the appender writes the address literal; it no longer reverse-resolves it.
                else -> "127.0.0.1"
            }
            if (expectedIp != null) {
                check(probe.header.remoteIp == expectedIp) {
                    "Remote IP appender resolved [${probe.header.remoteIp}], expected [$expectedIp]."
                }
            }
        } else {
            check(probe.header.remoteIp == null) { "No appender must leave the remote IP unset." }
        }
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            check(failureCount == 0) { "Command appender benchmark recorded $failureCount failure(s)." }
        } finally {
            gatewayScenario.close()
        }
    }

    @Benchmark
    fun extractCommandMessage(blackhole: Blackhole) {
        blackhole.consume(extract(request()))
    }

    @Benchmark
    fun handleRequestWaitSent(blackhole: Blackhole) {
        val response = runCatching {
            handlerFunction.handle(request()).block()
        }.getOrNull()
        if (response?.statusCode() != HttpStatus.OK) {
            failures.incrementAndGet()
        }
        blackhole.consume(response)
    }

    private fun extract(request: ServerRequest) = checkNotNull(
        extractor.extract(
            aggregateRouteMetadata = WebFluxBenchmarkSupport.cartAggregateRouteMetadata,
            commandBody = WebFluxBenchmarkSupport.addCartItemCommandBody(),
            request = request,
        ).block(),
    )

    private fun request(): ServerRequest {
        val builder = MockServerRequest.builder()
            .method(HttpMethod.POST)
            .pathVariable(MessageRecords.TENANT_ID, "benchmark-tenant")
            .pathVariable(MessageRecords.OWNER_ID, BenchmarkAggregates.FIXED_AGGREGATE_ID)
            .principal(UserPrincipal("benchmark-user"))
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.name)
            .header(HttpHeaders.USER_AGENT, USER_AGENT)
            .header("${CommandComponent.Header.COMMAND_HEADER_X_PREFIX}extended", "value")
        when (client) {
            "xff" -> builder
                .header(CommandRequestRemoteIpHeaderAppender.X_FORWARDED_FOR, "$CLIENT_IP, 10.0.0.1")
                .remoteAddress(reusedAddress)

            "loopback-reused" -> builder.remoteAddress(reusedAddress)
            "loopback-new" -> builder.remoteAddress(unresolvedAddress(LOOPBACK))
            "public-new" -> builder.remoteAddress(unresolvedAddress(PUBLIC_CLIENT))
        }
        return builder.body(WebFluxBenchmarkSupport.addCartItemCommandBody().toMono())
    }

    private companion object {
        const val USER_AGENT = "wow-benchmark/1.0"
        const val CLIENT_IP = "198.51.100.7"
        val LOOPBACK = byteArrayOf(127, 0, 0, 1)

        // TEST-NET-3 (RFC 5737): routable syntax, no real host; its reverse lookup goes to the configured resolver.
        val PUBLIC_CLIENT = byteArrayOf(203.toByte(), 0, 113, 7)

        /**
         * Reactor Netty builds the remote address from the raw bytes, without a host name, so the first
         * `getHostName()` on it performs a reverse lookup. `InetAddress.getByAddress` reproduces that.
         */
        fun unresolvedAddress(address: ByteArray): InetSocketAddress =
            InetSocketAddress(InetAddress.getByAddress(address), 54_321)
    }
}
