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

import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.errorResume
import me.ahoo.wow.webflux.route.toEventStreamResponse
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

fun Flux<CommandResult>.toCommandResponse(
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler
): Mono<ServerResponse> {
    if (!request.isSse()) {
        return this.next().toServerResponse(request, exceptionHandler)
    }

    val serverSentEventStream = this.map {
        ServerSentEvent.builder<String>()
            .id(it.id)
            .event(it.stage.name)
            .data(it.toJsonString())
            .build()
    }.errorResume(request, exceptionHandler)

    return serverSentEventStream.toEventStreamResponse(request, exceptionHandler)
}
