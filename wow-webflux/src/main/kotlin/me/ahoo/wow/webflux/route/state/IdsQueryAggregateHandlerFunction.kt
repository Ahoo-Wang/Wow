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

package me.ahoo.wow.webflux.route.state

import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.state.IdsQueryAggregateRouteSpec
import me.ahoo.wow.query.mask.tryMask
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.exception.toServerResponse
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.getTenantIdOrDefault
import org.springframework.core.ParameterizedTypeReference
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

object IdList : ParameterizedTypeReference<Set<String>>()

class IdsQueryAggregateHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: RequestExceptionHandler
) : HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantIdOrDefault(aggregateMetadata)
        return request.bodyToMono(IdList)
            .flatMapIterable {
                it.map { id ->
                    aggregateMetadata.aggregateId(id = id, tenantId = tenantId)
                }
            }
            .flatMap {
                stateAggregateRepository.load(it, aggregateMetadata.state)
            }.filter {
                it.initialized && !it.deleted
            }
            .map { it.state.tryMask() }
            .collectList()
            .toServerResponse(request, exceptionHandler)
    }
}

class IdsQueryAggregateHandlerFunctionFactory(
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: RequestExceptionHandler
) : RouteHandlerFunctionFactory<IdsQueryAggregateRouteSpec> {
    override val supportedSpec: Class<IdsQueryAggregateRouteSpec>
        get() = IdsQueryAggregateRouteSpec::class.java

    override fun create(spec: IdsQueryAggregateRouteSpec): HandlerFunction<ServerResponse> {
        return IdsQueryAggregateHandlerFunction(spec.aggregateMetadata, stateAggregateRepository, exceptionHandler)
    }
}
