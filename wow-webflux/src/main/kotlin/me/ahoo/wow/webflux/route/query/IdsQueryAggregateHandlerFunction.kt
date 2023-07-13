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
import me.ahoo.wow.openapi.query.TenantIdsQuery
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.exception.asServerResponse
import org.springframework.core.ParameterizedTypeReference
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

object IdList : ParameterizedTypeReference<Set<String>>()
object IdsQueryList : ParameterizedTypeReference<Set<TenantIdsQuery>>()

class IdsQueryAggregateHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val stateAggregateRepository: StateAggregateRepository,
    private val routeSpec: IdsQueryAggregateRouteSpec,
    private val exceptionHandler: ExceptionHandler
) : HandlerFunction<ServerResponse> {
    @Suppress("UNCHECKED_CAST")
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val parameterizedTypeReference: ParameterizedTypeReference<*> =
            if (routeSpec.requestBodyType == String::class.java) {
                IdList
            } else {
                IdsQueryList
            }
        return request.bodyToMono(parameterizedTypeReference)
            .flatMapIterable {
                if (routeSpec.requestBodyType == String::class.java) {
                    val ids = it as Set<String>
                    ids.map { id ->
                        aggregateMetadata.asAggregateId(id = id, tenantId = aggregateMetadata.staticTenantId!!)
                    }
                } else {
                    val ids = it as Set<TenantIdsQuery>
                    ids.map { tenantIdsQuery ->
                        aggregateMetadata.asAggregateId(id = tenantIdsQuery.id, tenantId = tenantIdsQuery.tenantId)
                    }
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
