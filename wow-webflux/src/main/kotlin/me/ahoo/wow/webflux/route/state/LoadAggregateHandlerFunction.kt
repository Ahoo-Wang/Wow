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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.exception.throwNotFoundIfEmpty
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.rest.RouteVariables
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.identity.aggregateId
import me.ahoo.wow.webflux.route.identity.identity
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

/** The state load routes: each reads one point of an aggregate's history, named by its route. */
enum class StateLoadRoute(val handlerKey: String) {
    /** The latest state. */
    LATEST(BuiltInHttpRouteHandlerKeys.State.LOAD_AGGREGATE) {
        override fun load(
            request: ServerRequest,
            repository: StateAggregateRepository,
            aggregateId: AggregateId,
            metadata: StateAggregateMetadata<*>,
        ): Mono<out StateAggregate<*>> = repository.load(aggregateId, metadata)
    },

    /** The state at the `{version}` path variable; a live aggregate that never reached it is an error. */
    VERSIONED(BuiltInHttpRouteHandlerKeys.State.LOAD_VERSIONED_AGGREGATE) {
        override fun load(
            request: ServerRequest,
            repository: StateAggregateRepository,
            aggregateId: AggregateId,
            metadata: StateAggregateMetadata<*>,
        ): Mono<out StateAggregate<*>> {
            val version = request.pathVariable(RouteVariables.VERSION).toInt()
            return repository.load(aggregateId, metadata, version).doOnNext {
                if (it.initialized && !it.deleted) {
                    check(version == it.version) {
                        "targetVersion:$version != stateAggregate.version:${it.version}"
                    }
                }
            }
        }
    },

    /** The state at the `{createTime}` path variable. */
    TIME_BASED(BuiltInHttpRouteHandlerKeys.State.LOAD_TIME_BASED_AGGREGATE) {
        override fun load(
            request: ServerRequest,
            repository: StateAggregateRepository,
            aggregateId: AggregateId,
            metadata: StateAggregateMetadata<*>,
        ): Mono<out StateAggregate<*>> =
            repository.load(aggregateId, metadata, request.pathVariable(RouteVariables.CREATE_TIME).toLong())
    };

    abstract fun load(
        request: ServerRequest,
        repository: StateAggregateRepository,
        aggregateId: AggregateId,
        metadata: StateAggregateMetadata<*>,
    ): Mono<out StateAggregate<*>>
}

/**
 * Loads the state [route] names. A missing or deleted aggregate is `404`; an owned aggregate is read only by its owner
 * ([OwnerAggregatePrecondition]); under [admission] the state is admitted and masked as a query result is.
 */
class LoadAggregateHandlerFunction(
    private val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: RequestExceptionHandler,
    private val admission: PointReadAdmission = PointReadAdmission.DISABLED,
    private val route: StateLoadRoute = StateLoadRoute.LATEST,
) : HandlerFunction<ServerResponse> {
    private val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata

    override fun handle(request: ServerRequest): Mono<ServerResponse> =
        // Deferred: an identity error (a blank path variable, a V3 conflict) is a signal the error mapping sees.
        Mono.defer {
            val identity = request.identity(aggregateRouteMetadata)
            val aggregateId = identity.aggregateId(aggregateMetadata)
            val ownerPrecondition = OwnerAggregatePrecondition(identity, aggregateRouteMetadata.ownerPolicy)
            route.load(request, stateAggregateRepository, aggregateId, aggregateMetadata.state)
                .filter { it.initialized && !it.deleted }
                .flatMap {
                    ownerPrecondition.check(it)
                    admission.state(aggregateMetadata, request, it)
                }
                .throwNotFoundIfEmpty()
        }.toServerResponse(request, exceptionHandler)
}

class LoadAggregateHandlerFunctionFactory(
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: RequestExceptionHandler,
    private val admission: PointReadAdmission = PointReadAdmission.DISABLED,
    private val route: StateLoadRoute = StateLoadRoute.LATEST,
) : AggregateRouteHandlerFunctionFactorySupport(route.handlerKey) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> = LoadAggregateHandlerFunction(
        aggregateRouteMetadata(metadata),
        stateAggregateRepository,
        exceptionHandler,
        admission,
        route,
    )
}
