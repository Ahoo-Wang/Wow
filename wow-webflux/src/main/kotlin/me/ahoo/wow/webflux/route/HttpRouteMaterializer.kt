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

import me.ahoo.wow.openapi.contract.HttpRouteContract
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.RequestPredicate
import org.springframework.web.reactive.function.server.ServerResponse

data class HttpRouteBinding(
    val predicate: RequestPredicate,
    val handlerFunction: HandlerFunction<ServerResponse>
)

class HttpRouteMaterializer(
    private val routeHandlerFunctionRegistrar: RouteHandlerFunctionRegistrar,
    private val predicateFactory: HttpRoutePredicateFactory = HttpRoutePredicateFactory()
) {
    fun materialize(contract: HttpRouteContract): HttpRouteBinding {
        val factory = routeHandlerFunctionRegistrar.requireHttpFactory(contract)
        return HttpRouteBinding(
            predicate = predicateFactory.create(contract),
            handlerFunction = factory.create(contract)
        )
    }
}
