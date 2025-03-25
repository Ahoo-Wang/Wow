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

import me.ahoo.wow.exception.throwNotFoundIfEmpty
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.aggregate.state.LoadTimeBasedAggregateRouteSpec
import me.ahoo.wow.query.mask.tryMask
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.getAggregateId
import me.ahoo.wow.webflux.route.command.getTenantIdOrDefault
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class LoadTimeBasedAggregateHandlerFunction(
    private val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: RequestExceptionHandler
) : HandlerFunction<ServerResponse> {
    private val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantIdOrDefault(aggregateMetadata)
        val id = requireNotNull(request.getAggregateId(aggregateRouteMetadata.owner))
        val aggregateId = aggregateMetadata.aggregateId(id = id, tenantId = tenantId)
        val tailEventTime = request.pathVariable(MessageRecords.CREATE_TIME).toLong()
        return stateAggregateRepository
            .load(aggregateId, aggregateMetadata.state, tailEventTime)
            .filter {
                it.initialized && !it.deleted
            }
            .map {
                OwnerAggregatePrecondition(request, aggregateRouteMetadata.owner).check(it)
                it.state.tryMask()
            }
            .throwNotFoundIfEmpty()
            .toServerResponse(request, exceptionHandler)
    }
}

class LoadTimeBasedAggregateHandlerFunctionFactory(
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: RequestExceptionHandler
) : RouteHandlerFunctionFactory<LoadTimeBasedAggregateRouteSpec> {
    override val supportedSpec: Class<LoadTimeBasedAggregateRouteSpec>
        get() = LoadTimeBasedAggregateRouteSpec::class.java

    override fun create(spec: LoadTimeBasedAggregateRouteSpec): HandlerFunction<ServerResponse> {
        return LoadTimeBasedAggregateHandlerFunction(
            spec.aggregateRouteMetadata,
            stateAggregateRepository,
            exceptionHandler
        )
    }
}
