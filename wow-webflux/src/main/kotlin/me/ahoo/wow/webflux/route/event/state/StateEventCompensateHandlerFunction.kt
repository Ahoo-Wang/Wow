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

package me.ahoo.wow.webflux.route.event.state

import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.event.state.StateEventCompensateRouteSpec
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.EventCompensateHandlerFunction
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerResponse

class StateEventCompensateHandlerFunction(
    override val aggregateMetadata: AggregateMetadata<*, *>,
    override val eventCompensator: StateEventCompensator,
    override val exceptionHandler: ExceptionHandler
) : EventCompensateHandlerFunction()

class StateEventCompensateHandlerFunctionFactory(
    private val eventCompensator: StateEventCompensator,
    private val exceptionHandler: ExceptionHandler
) : RouteHandlerFunctionFactory<StateEventCompensateRouteSpec> {
    override val supportedSpec: Class<StateEventCompensateRouteSpec>
        get() = StateEventCompensateRouteSpec::class.java

    override fun create(spec: StateEventCompensateRouteSpec): HandlerFunction<ServerResponse> {
        return StateEventCompensateHandlerFunction(spec.aggregateMetadata, eventCompensator, exceptionHandler)
    }
}
