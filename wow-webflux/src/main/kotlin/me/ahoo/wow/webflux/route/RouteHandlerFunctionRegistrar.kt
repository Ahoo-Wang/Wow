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

import me.ahoo.wow.openapi.RouteSpec
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class RouteHandlerFunctionRegistrar {
    companion object {
        private val log = LoggerFactory.getLogger(RouteHandlerFunctionRegistrar::class.java)
    }

    private val factories = ConcurrentHashMap<Class<out RouteSpec>, RouteHandlerFunctionFactory<*>>()
    fun register(routeHandlerFunctionFactory: RouteHandlerFunctionFactory<*>) {
        val previous = factories.put(routeHandlerFunctionFactory.supportedSpec, routeHandlerFunctionFactory)
        if (log.isInfoEnabled) {
            log.info(
                "Register - supportedSpec:[{}] - previous:[{}],current:[{}].",
                routeHandlerFunctionFactory.supportedSpec,
                previous,
                routeHandlerFunctionFactory
            )
        }
    }

    fun getFactory(spec: RouteSpec): RouteHandlerFunctionFactory<*>? {
        return factories[spec::class.java]
    }
}
