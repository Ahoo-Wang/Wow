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

import me.ahoo.wow.openapi.RouterSpecs
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * [org.springframework.web.reactive.function.server.support.RouterFunctionMapping]
 */
@Suppress("LongParameterList")
class RouterFunctionBuilder(
    private val routerSpecs: RouterSpecs,
    routeHandlerFunctionRegistrar: RouteHandlerFunctionRegistrar
) {
    private val routeMaterializer = HttpRouteMaterializer(routeHandlerFunctionRegistrar)

    fun build(): RouterFunction<ServerResponse> {
        val routerFunctionBuilder = RouterFunctions.route()
        for (contract in routerSpecs.toRouteCatalog().routes) {
            val binding = routeMaterializer.materialize(contract)
            routerFunctionBuilder.route(
                binding.predicate,
                binding.handlerFunction
            )
        }
        return routerFunctionBuilder.build()
    }
}
