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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.query.IdsQueryAggregateRouteSpec
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.exception.asServerResponse
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.CommandParser.getTenantId
import org.springframework.core.ParameterizedTypeReference
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

object IdList : ParameterizedTypeReference<Set<String>>()

class IdsQueryAggregateHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: ExceptionHandler
) : HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantId(aggregateMetadata)
        return request.bodyToMono(IdList)
            .flatMapIterable {
                it.map { id ->
                    aggregateMetadata.asAggregateId(id = id, tenantId = tenantId)
                }
            }
            .flatMap {
                stateAggregateRepository.load(aggregateMetadata.state, it)
            }.filter {
                it.initialized && !it.deleted
            }
            .map { it.state }
            .collectList()
            .asServerResponse(exceptionHandler)
    }
}

class IdsQueryAggregateHandlerFunctionFactory(
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: ExceptionHandler
) : RouteHandlerFunctionFactory<IdsQueryAggregateRouteSpec> {
    override val supportedSpec: Class<IdsQueryAggregateRouteSpec>
        get() = IdsQueryAggregateRouteSpec::class.java

    override fun create(spec: IdsQueryAggregateRouteSpec): HandlerFunction<ServerResponse> {
        return IdsQueryAggregateHandlerFunction(spec.aggregateMetadata, stateAggregateRepository, exceptionHandler)
    }
}
