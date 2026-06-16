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

import io.github.oshai.kotlinlogging.KotlinLogging

class RouteHandlerFunctionRegistrar(
    httpFactories: Collection<HttpRouteHandlerFunctionFactory> = emptyList()
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val httpFactories: MutableMap<String, HttpRouteHandlerFunctionFactory> =
        httpFactories.associateBy { it.handlerKey }.toMutableMap()

    fun registerHttpFactory(httpRouteHandlerFunctionFactory: HttpRouteHandlerFunctionFactory) {
        val previous = httpFactories.put(httpRouteHandlerFunctionFactory.handlerKey, httpRouteHandlerFunctionFactory)
        log.info {
            "Register - handlerKey:[${httpRouteHandlerFunctionFactory.handlerKey}] - previous:[$previous],current:[$httpRouteHandlerFunctionFactory]."
        }
    }

    fun getHttpFactory(handlerKey: String): HttpRouteHandlerFunctionFactory? {
        return httpFactories[handlerKey]
    }
}
