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

import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.exception.throwNotFoundIfEmpty
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.rest.RouteVariables
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.identity.identity
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class LoadTimeBasedAggregateHandlerFunction(
    private val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: RequestExceptionHandler,
    private val admission: PointReadAdmission = PointReadAdmission.DISABLED,
) : HandlerFunction<ServerResponse> {
    private val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata

    override fun handle(request: ServerRequest): Mono<ServerResponse> =
        // Deferred: an identity error (a blank path variable, a V3 conflict) is a signal the error mapping sees.
        Mono.defer {
            val identity = request.identity(aggregateRouteMetadata)
            val tenantId = identity.tenantId() ?: TenantId.DEFAULT_TENANT_ID
            val id = requireNotNull(identity.aggregateId())
            val aggregateId = aggregateMetadata.aggregateId(id = id, tenantId = tenantId)
            val tailEventTime = request.pathVariable(RouteVariables.CREATE_TIME).toLong()
            stateAggregateRepository
                .load(aggregateId, aggregateMetadata.state, tailEventTime)
                .filter {
                    it.initialized && !it.deleted
                }
                .flatMap {
                    OwnerAggregatePrecondition(identity, aggregateRouteMetadata.ownerPolicy).check(it)
                    admission.state(aggregateMetadata, request, it)
                }
                .throwNotFoundIfEmpty()
        }.toServerResponse(request, exceptionHandler)
}

class LoadTimeBasedAggregateHandlerFunctionFactory(
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: RequestExceptionHandler,
    private val admission: PointReadAdmission = PointReadAdmission.DISABLED,
) : AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.State.LOAD_TIME_BASED_AGGREGATE) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        return create(aggregateRouteMetadata(metadata))
    }

    private fun create(aggregateRouteMetadata: AggregateRouteMetadata<*>): HandlerFunction<ServerResponse> {
        return LoadTimeBasedAggregateHandlerFunction(
            aggregateRouteMetadata,
            stateAggregateRepository,
            exceptionHandler,
            admission,
        )
    }
}
