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

package me.ahoo.wow.webflux.route.id

import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.openapi.global.GenerateGlobalIdRouteSpec
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class GlobalIdHandlerFunction : HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        return ServerResponse
            .ok()
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue(GlobalIdGenerator.generateAsString())
    }
}

class GlobalIdHandlerFunctionFactory : RouteHandlerFunctionFactory<GenerateGlobalIdRouteSpec> {
    override val supportedSpec: Class<GenerateGlobalIdRouteSpec>
        get() = GenerateGlobalIdRouteSpec::class.java

    override fun create(spec: GenerateGlobalIdRouteSpec): HandlerFunction<ServerResponse> {
        return GlobalIdHandlerFunction()
    }
}
