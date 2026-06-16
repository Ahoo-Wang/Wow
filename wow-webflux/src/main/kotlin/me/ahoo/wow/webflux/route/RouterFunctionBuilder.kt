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
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * [org.springframework.web.reactive.function.server.support.RouterFunctionMapping]
 */
@Suppress("LongParameterList")
class RouterFunctionBuilder(
    private val routerSpecs: RouterSpecs,
    private val routeHandlerFunctionRegistrar: RouteHandlerFunctionRegistrar
) {

    fun build(): RouterFunction<ServerResponse> {
        val routerFunctionBuilder = RouterFunctions.route()
        for (contract in routerSpecs.toRouteCatalog().routes) {
            val acceptMediaTypes = MediaType.parseMediaTypes(contract.accept).toTypedArray()
            val acceptPredicate = RequestPredicates.accept(*acceptMediaTypes)
            val httpMethod = HttpMethod.valueOf(contract.method)
            val requestPredicate = RequestPredicates.path(contract.path)
                .and(RequestPredicates.method(httpMethod))
                .and(acceptPredicate)

            val factory = requireNotNull(routeHandlerFunctionRegistrar.getHttpFactory(contract.handlerKey)) {
                "HttpRouteHandlerFunctionFactory not found - handlerKey:[${contract.handlerKey}], " +
                    "method:[${contract.method}], path:[${contract.path}], routeId:[${contract.routeId}]."
            }
            val handlerFunction = factory.create(contract, contract.handlerMetadata)
            routerFunctionBuilder.route(
                requestPredicate,
                handlerFunction
            )
        }
        return routerFunctionBuilder.build()
    }
}
