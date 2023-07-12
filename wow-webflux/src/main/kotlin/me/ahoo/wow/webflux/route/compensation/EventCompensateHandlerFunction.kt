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

package me.ahoo.wow.webflux.route.compensation

import me.ahoo.wow.messaging.compensation.CompensationConfig
import me.ahoo.wow.messaging.compensation.EventCompensator
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.exception.asServerResponse
import me.ahoo.wow.webflux.route.command.CommandParser.getTenantId
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

abstract class EventCompensateHandlerFunction : HandlerFunction<ServerResponse> {

    abstract val aggregateMetadata: AggregateMetadata<*, *>
    abstract val eventCompensator: EventCompensator
    abstract val exceptionHandler: ExceptionHandler
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantId(aggregateMetadata)
        val id = request.pathVariable(RoutePaths.ID_KEY)
        return request.bodyToMono(CompensationConfig::class.java)
            .flatMap {
                requireNotNull(it) {
                    "CompensationConfig is required!"
                }
                val headVersion = request.pathVariable(RoutePaths.COMPENSATE_HEAD_VERSION_KEY).toInt()
                val tailVersion = request.pathVariable(RoutePaths.COMPENSATE_TAIL_VERSION_KEY).toInt()
                val aggregateId = aggregateMetadata.asAggregateId(id = id, tenantId = tenantId)
                eventCompensator.compensate(
                    aggregateId,
                    config = it,
                    headVersion = headVersion,
                    tailVersion = tailVersion,
                )
            }.asServerResponse(exceptionHandler)
    }
}
