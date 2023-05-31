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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.exception.asServerResponse
import me.ahoo.wow.webflux.route.CommandHandlerFunction.Companion.sendCommand
import me.ahoo.wow.webflux.route.CommandParser.parse
import me.ahoo.wow.webflux.route.appender.RoutePaths
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import java.time.Duration

class DeleteAggregateHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val commandGateway: CommandGateway,
    private val timeout: Duration = DEFAULT_TIME_OUT,
    private val exceptionHandler: ExceptionHandler
) : HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val id = request.pathVariable(RoutePaths.ID_KEY)
        return request.parse(
            aggregateMetadata = aggregateMetadata,
            commandBody = DefaultDeleteAggregate,
            aggregateId = id,
        )
            .flatMap {
                request.sendCommand(commandGateway, it, timeout)
            }
            .asServerResponse(exceptionHandler)
    }
}
