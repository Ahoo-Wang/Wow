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

package me.ahoo.wow.webflux.route.event

import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.event.DomainEventCompensateRouteSpec
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerResponse

class DomainEventCompensateHandlerFunction(
    override val aggregateMetadata: AggregateMetadata<*, *>,
    override val eventCompensator: DomainEventCompensator,
    override val exceptionHandler: ExceptionHandler
) : EventCompensateHandlerFunction()

class DomainEventCompensateHandlerFunctionFactory(
    private val eventCompensator: DomainEventCompensator,
    private val exceptionHandler: ExceptionHandler
) : RouteHandlerFunctionFactory<DomainEventCompensateRouteSpec> {
    override val supportedSpec: Class<DomainEventCompensateRouteSpec>
        get() = DomainEventCompensateRouteSpec::class.java

    override fun create(spec: DomainEventCompensateRouteSpec): HandlerFunction<ServerResponse> {
        return DomainEventCompensateHandlerFunction(spec.aggregateMetadata, eventCompensator, exceptionHandler)
    }
}
