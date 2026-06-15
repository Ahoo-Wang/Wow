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
import me.ahoo.wow.openapi.aggregate.command.CommandFacadeRouteSpec
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.extractor.CommandFacadeBodyExtractor
import me.ahoo.wow.webflux.route.command.extractor.CommandMessageExtractor
import me.ahoo.wow.webflux.route.policy.CommandWaitPolicy
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import java.time.Duration

/**
 * [org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping]
 *
 * [org.springframework.web.reactive.function.server.support.RouterFunctionMapping]
 */
class CommandFacadeHandlerFunction(
    private val commandGateway: CommandGateway,
    private val commandMessageExtractor: CommandMessageExtractor,
    private val exceptionHandler: RequestExceptionHandler,
    private val commandWaitPolicy: CommandWaitPolicy
) : HandlerFunction<ServerResponse> {
    constructor(
        commandGateway: CommandGateway,
        commandMessageExtractor: CommandMessageExtractor,
        exceptionHandler: RequestExceptionHandler,
        timeout: Duration = DEFAULT_TIME_OUT
    ) : this(
        commandGateway = commandGateway,
        commandMessageExtractor = commandMessageExtractor,
        exceptionHandler = exceptionHandler,
        commandWaitPolicy = CommandWaitPolicy(timeout)
    )

    private val handler = CommandHandler(
        commandGateway = commandGateway,
        commandMessageExtractor = commandMessageExtractor,
        commandWaitPolicy = commandWaitPolicy
    )

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        return request.body(CommandFacadeBodyExtractor).switchIfEmpty {
            Mono.error(IllegalArgumentException("Command can not be empty."))
        }.flatMapMany {
            handler.handle(request, it.t1, it.t2)
        }.toCommandResponse(request, exceptionHandler)
    }
}

class CommandFacadeHandlerFunctionFactory(
    private val commandGateway: CommandGateway,
    private val commandMessageExtractor: CommandMessageExtractor,
    private val exceptionHandler: RequestExceptionHandler,
    private val commandWaitPolicy: CommandWaitPolicy
) : RouteHandlerFunctionFactory<CommandFacadeRouteSpec> {
    constructor(
        commandGateway: CommandGateway,
        commandMessageExtractor: CommandMessageExtractor,
        exceptionHandler: RequestExceptionHandler,
        timeout: Duration = DEFAULT_TIME_OUT
    ) : this(
        commandGateway = commandGateway,
        commandMessageExtractor = commandMessageExtractor,
        exceptionHandler = exceptionHandler,
        commandWaitPolicy = CommandWaitPolicy(timeout)
    )

    override val supportedSpec: Class<CommandFacadeRouteSpec>
        get() = CommandFacadeRouteSpec::class.java

    override fun create(spec: CommandFacadeRouteSpec): HandlerFunction<ServerResponse> {
        return CommandFacadeHandlerFunction(
            commandGateway = commandGateway,
            commandMessageExtractor = commandMessageExtractor,
            exceptionHandler = exceptionHandler,
            commandWaitPolicy = commandWaitPolicy
        )
    }
}
