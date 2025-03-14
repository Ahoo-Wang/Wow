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

import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.event.EventCompensateRouteSpec
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.getTenantIdOrDefault
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class EventCompensateHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val eventCompensateSupporter: EventCompensateSupporter,
    private val exceptionHandler: RequestExceptionHandler
) : HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantIdOrDefault(aggregateMetadata)
        val id = request.pathVariable(RoutePaths.ID_KEY)
        return request.bodyToMono(CompensationTarget::class.java)
            .flatMap {
                requireNotNull(it) {
                    "CompensationTarget is required!"
                }
                val version = request.pathVariable(MessageRecords.VERSION).toInt()
                val aggregateId = aggregateMetadata.aggregateId(id = id, tenantId = tenantId)

                eventCompensateSupporter.compensate(
                    aggregateId = aggregateId,
                    target = it,
                    version = version
                )
            }.toServerResponse(request, exceptionHandler)
    }
}

class EventCompensateHandlerFunctionFactory(
    private val eventCompensateSupporter: EventCompensateSupporter,
    private val exceptionHandler: RequestExceptionHandler
) : RouteHandlerFunctionFactory<EventCompensateRouteSpec> {
    override val supportedSpec: Class<EventCompensateRouteSpec>
        get() = EventCompensateRouteSpec::class.java

    override fun create(spec: EventCompensateRouteSpec): HandlerFunction<ServerResponse> {
        return EventCompensateHandlerFunction(
            spec.aggregateMetadata,
            eventCompensateSupporter,
            exceptionHandler
        )
    }
}
