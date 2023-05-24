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

import me.ahoo.wow.api.Wow
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.WaitStrategyRegistrar
import org.springdoc.core.fn.builders.operation.Builder
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.RequestPredicates.accept
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import java.util.function.Consumer

val EMPTY_OK = ServerResponse
    .ok()
    .contentType(MediaType.APPLICATION_JSON)
    .build()
const val COMMAND_WAIT_HANDLER_PATH = "/${Wow.WOW}/command/wait"

class CommandWaitHandlerFunction(
    private val waitStrategyRegistrar: WaitStrategyRegistrar
) : HandlerFunction<ServerResponse> {

    val routerFunction: RouterFunction<ServerResponse> by lazy {
        buildRouterFunction()
    }

    private fun buildRouterFunction(): RouterFunction<ServerResponse> =
        SpringdocRouteBuilder.route()
            .POST(
                COMMAND_WAIT_HANDLER_PATH,
                accept(MediaType.APPLICATION_JSON),
                this,
                commandWaitOperation(),
            ).build()

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        return request
            .bodyToMono(SimpleWaitSignal::class.java)
            .map {
                waitStrategyRegistrar.next(it)
            }.flatMap {
                EMPTY_OK
            }
    }

    private fun commandWaitOperation(): Consumer<Builder> {
        return Consumer<Builder> {
            it
                .tag(Wow.WOW)
                .summary("command wait handler")
                .operationId("${Wow.WOW_PREFIX}command.wait")
                .requestBody(
                    org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder()
                        .required(true)
                        .implementation(SimpleWaitSignal::class.java),
                )
                .response(
                    org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder()
                        .responseCode(HttpStatus.OK.value().toString())
                        .description(HttpStatus.OK.reasonPhrase),
                )
        }
    }
}
