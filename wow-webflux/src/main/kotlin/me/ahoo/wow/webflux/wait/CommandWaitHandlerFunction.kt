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

package me.ahoo.wow.webflux.wait

import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.WaitStrategyRegistrar
import me.ahoo.wow.openapi.global.CommandWaitRouteSpec
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

val EMPTY_OK = ServerResponse
    .ok()
    .contentType(MediaType.APPLICATION_JSON)
    .build()

class CommandWaitHandlerFunction(
    private val waitStrategyRegistrar: WaitStrategyRegistrar
) : HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        return request
            .bodyToMono(SimpleWaitSignal::class.java)
            .map {
                waitStrategyRegistrar.next(it)
            }.flatMap {
                EMPTY_OK
            }
    }
}

class CommandWaitHandlerFunctionFactory(private val waitStrategyRegistrar: WaitStrategyRegistrar) :
    RouteHandlerFunctionFactory<CommandWaitRouteSpec> {
    override val supportedSpec: Class<CommandWaitRouteSpec>
        get() = CommandWaitRouteSpec::class.java

    override fun create(spec: CommandWaitRouteSpec): HandlerFunction<ServerResponse> {
        return CommandWaitHandlerFunction(waitStrategyRegistrar)
    }
}
