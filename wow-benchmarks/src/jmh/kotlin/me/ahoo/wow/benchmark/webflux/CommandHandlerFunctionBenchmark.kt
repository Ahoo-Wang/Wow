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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.benchmark.scenario.CommandGatewayScenario
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import me.ahoo.wow.webflux.route.command.CommandHandlerFunction
import me.ahoo.wow.webflux.route.command.toCommandResponse
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import org.springframework.http.HttpStatus
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger

@State(Scope.Benchmark)
open class CommandHandlerFunctionBenchmark {
    private lateinit var gatewayScenario: CommandGatewayScenario
    private lateinit var handlerFunction: CommandHandlerFunction
    private lateinit var preparedRequest: org.springframework.web.reactive.function.server.ServerRequest
    private lateinit var preparedCommandBody: AddCartItem
    private lateinit var preparedCommandMessage: CommandMessage<Any>
    private lateinit var preparedCommandResult: CommandResult
    private val failures = AtomicInteger()

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        gatewayScenario = CommandGatewayScenario.create(subscribeToCart = false)
        handlerFunction = CommandHandlerFunction(
            aggregateRouteMetadata = WebFluxBenchmarkSupport.cartAggregateRouteMetadata,
            commandRouteMetadata = WebFluxBenchmarkSupport.addCartItemRouteMetadata,
            commandGateway = gatewayScenario.commandGateway,
            commandMessageExtractor = WebFluxBenchmarkSupport.commandMessageExtractor,
            exceptionHandler = DefaultRequestExceptionHandler,
        )
        preparedRequest = WebFluxBenchmarkSupport.addCartItemRequest()
        preparedCommandBody = WebFluxBenchmarkSupport.addCartItemCommandBody()
        preparedCommandMessage = WebFluxBenchmarkSupport.commandMessageExtractor.extract(
            aggregateRouteMetadata = WebFluxBenchmarkSupport.cartAggregateRouteMetadata,
            commandBody = preparedCommandBody,
            request = preparedRequest,
        ).block()!!
        preparedCommandResult = WebFluxBenchmarkSupport.commandResult(preparedCommandMessage)
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            if (failureCount > 0) {
                throw IllegalStateException(
                    "Command handler WebFlux benchmark recorded $failureCount failure(s).",
                )
            }
        } finally {
            gatewayScenario.close()
        }
    }

    @Benchmark
    fun buildAddCartItemRequest(blackhole: Blackhole) {
        blackhole.consume(WebFluxBenchmarkSupport.addCartItemRequest())
    }

    @Benchmark
    fun extractCommandMessage(blackhole: Blackhole) {
        val commandMessage = WebFluxBenchmarkSupport.commandMessageExtractor.extract(
            aggregateRouteMetadata = WebFluxBenchmarkSupport.cartAggregateRouteMetadata,
            commandBody = preparedCommandBody,
            request = preparedRequest,
        ).block()
        blackhole.consume(commandMessage)
    }

    @Benchmark
    fun sendWaitSentCoreFromExtractedMessage(blackhole: Blackhole) {
        // The in-memory bus marks sent messages read-only, so each send needs a mutable header copy.
        val commandMessage = preparedCommandMessage.copy()
        val result = gatewayScenario.commandGateway
            .sendAndWait(
                command = commandMessage,
                waitPlan = CommandWait.sent(commandMessage.commandId),
            )
            .block()
        blackhole.consume(result)
    }

    @Benchmark
    fun commandResultJsonResponse(blackhole: Blackhole) {
        val response = Flux.just(preparedCommandResult)
            .toCommandResponse(preparedRequest, DefaultRequestExceptionHandler)
            .block()
        blackhole.consume(response)
    }

    @Benchmark
    fun postAddCartItemWaitSent(blackhole: Blackhole) {
        val response = runCatching {
            handlerFunction.handle(WebFluxBenchmarkSupport.addCartItemRequest()).block()
        }.onFailure {
            failures.incrementAndGet()
        }.getOrNull()
        if (response?.statusCode() != HttpStatus.OK) {
            failures.incrementAndGet()
        }
        blackhole.consume(response)
    }
}
