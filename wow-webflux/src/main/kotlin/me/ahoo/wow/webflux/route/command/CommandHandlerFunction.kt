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

package me.ahoo.wow.webflux.route.command

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.openapi.aggregate.command.CommandRouteSpec
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.CommandRouteMetadata
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.extractor.CommandBodyExtractor
import me.ahoo.wow.webflux.route.command.extractor.CommandMessageExtractor
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import java.time.Duration

val DEFAULT_TIME_OUT: Duration = Duration.ofSeconds(30)

/**
 * [org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping]
 *
 * [org.springframework.web.reactive.function.server.support.RouterFunctionMapping]
 */
class CommandHandlerFunction(
    private val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    private val commandRouteMetadata: CommandRouteMetadata<out Any>,
    private val commandGateway: CommandGateway,
    private val commandMessageExtractor: CommandMessageExtractor,
    private val exceptionHandler: RequestExceptionHandler,
    private val timeout: Duration = DEFAULT_TIME_OUT
) : HandlerFunction<ServerResponse> {
    private val bodyExtractor = CommandBodyExtractor(commandRouteMetadata)
    private val handler = CommandHandler(commandGateway, commandMessageExtractor, timeout)
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        return if (commandRouteMetadata.pathVariableMetadata.isEmpty() && commandRouteMetadata.headerVariableMetadata.isEmpty()) {
            request.bodyToMono(commandRouteMetadata.commandMetadata.commandType)
        } else {
            request.body(
                bodyExtractor,
                mapOf(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE to request.pathVariables()),
            )
        }.switchIfEmpty {
            Mono.error(IllegalArgumentException("Command can not be empty."))
        }.flatMapMany {
            handler.handle(request, it, aggregateRouteMetadata)
        }.toCommandResponse(request, exceptionHandler)
    }
}

class CommandHandlerFunctionFactory(
    private val commandGateway: CommandGateway,
    private val commandMessageExtractor: CommandMessageExtractor,
    private val exceptionHandler: RequestExceptionHandler,
    private val timeout: Duration = DEFAULT_TIME_OUT
) : RouteHandlerFunctionFactory<CommandRouteSpec> {
    override val supportedSpec: Class<CommandRouteSpec>
        get() = CommandRouteSpec::class.java

    @Suppress("UNCHECKED_CAST")
    override fun create(spec: CommandRouteSpec): HandlerFunction<ServerResponse> {
        return CommandHandlerFunction(
            aggregateRouteMetadata = spec.aggregateRouteMetadata,
            commandRouteMetadata = spec.commandRouteMetadata as CommandRouteMetadata<Any>,
            commandGateway = commandGateway,
            commandMessageExtractor = commandMessageExtractor,
            exceptionHandler = exceptionHandler,
            timeout = timeout
        )
    }
}
