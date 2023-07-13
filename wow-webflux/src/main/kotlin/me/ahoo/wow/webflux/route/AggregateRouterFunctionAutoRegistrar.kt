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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.wait.WaitStrategyRegistrar
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.Router
import me.ahoo.wow.openapi.command.CommandRouteSpec
import me.ahoo.wow.openapi.command.CommandWaitRouteSpec
import me.ahoo.wow.openapi.command.DefaultDeleteAggregateRouteSpec
import me.ahoo.wow.openapi.compensation.DomainEventCompensateRouteSpec
import me.ahoo.wow.openapi.compensation.StateEventCompensateRouteSpec
import me.ahoo.wow.openapi.query.AggregateTracingRouteSpec
import me.ahoo.wow.openapi.query.LoadAggregateRouteSpec
import me.ahoo.wow.openapi.query.ScanAggregateRouteSpec
import me.ahoo.wow.openapi.route.CommandRouteMetadata
import me.ahoo.wow.openapi.snapshot.BatchRegenerateSnapshotRouteSpec
import me.ahoo.wow.openapi.snapshot.RegenerateSnapshotRouteSpec
import me.ahoo.wow.openapi.state.RegenerateStateEventRouteSpec
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.route.command.CommandHandlerFunction
import me.ahoo.wow.webflux.route.command.DeleteAggregateHandlerFunction
import me.ahoo.wow.webflux.route.compensation.DomainEventCompensateHandlerFunction
import me.ahoo.wow.webflux.route.compensation.StateEventCompensateHandlerFunction
import me.ahoo.wow.webflux.route.query.AggregateTracingHandlerFunction
import me.ahoo.wow.webflux.route.query.LoadAggregateHandlerFunction
import me.ahoo.wow.webflux.route.query.ScanAggregateHandlerFunction
import me.ahoo.wow.webflux.route.snapshot.BatchRegenerateSnapshotHandlerFunction
import me.ahoo.wow.webflux.route.snapshot.RegenerateSnapshotHandlerFunction
import me.ahoo.wow.webflux.route.state.RegenerateStateEventFunction
import me.ahoo.wow.webflux.wait.CommandWaitHandlerFunction
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * [org.springframework.web.reactive.function.server.support.RouterFunctionMapping]
 */
@Suppress("LongParameterList")
class AggregateRouterFunctionAutoRegistrar(
    private val router: Router,
    private val waitStrategyRegistrar: WaitStrategyRegistrar,
    private val commandGateway: CommandGateway,
    private val stateAggregateRepository: StateAggregateRepository,
    private val stateAggregateFactory: StateAggregateFactory,
    private val snapshotRepository: SnapshotRepository,
    private val eventStore: EventStore,
    private val domainEventCompensator: DomainEventCompensator,
    private val stateEventCompensator: StateEventCompensator,
    private val exceptionHandler: ExceptionHandler
) {
    val routerFunction: RouterFunction<ServerResponse> by lazy {
        buildRouterFunction()
    }

    @Suppress("LongMethod")
    private fun buildRouterFunction(): RouterFunction<ServerResponse> {
        check(router.isNotEmpty()) {
            "router is empty!"
        }
        val routerFunctionBuilder = RouterFunctions.route()
        val acceptPredicate = RequestPredicates.accept(MediaType.APPLICATION_JSON)
        for (routeSpec in router) {
            val httpMethod = HttpMethod.valueOf(routeSpec.method)
            val requestPredicate =
                RequestPredicates.path(routeSpec.path).and(RequestPredicates.method(httpMethod)).and(acceptPredicate)
            when (routeSpec) {
                is CommandWaitRouteSpec -> {
                    routerFunctionBuilder.route(requestPredicate, CommandWaitHandlerFunction(waitStrategyRegistrar))
                }

                is LoadAggregateRouteSpec -> {
                    routerFunctionBuilder.route(
                        requestPredicate,
                        LoadAggregateHandlerFunction(
                            aggregateMetadata = routeSpec.aggregateMetadata,
                            stateAggregateRepository = stateAggregateRepository,
                            exceptionHandler = exceptionHandler,
                        )
                    )
                }

                is ScanAggregateRouteSpec -> {
                    routerFunctionBuilder.route(
                        requestPredicate,
                        ScanAggregateHandlerFunction(
                            aggregateMetadata = routeSpec.aggregateMetadata,
                            stateAggregateRepository = stateAggregateRepository,
                            eventStore = eventStore,
                            exceptionHandler = exceptionHandler,
                        )
                    )
                }

                is AggregateTracingRouteSpec -> {
                    routerFunctionBuilder.route(
                        requestPredicate,
                        AggregateTracingHandlerFunction(
                            aggregateMetadata = routeSpec.aggregateMetadata,
                            eventStore = eventStore,
                            exceptionHandler = exceptionHandler,
                        )
                    )
                }

                is RegenerateSnapshotRouteSpec -> {
                    routerFunctionBuilder.route(
                        requestPredicate,
                        RegenerateSnapshotHandlerFunction(
                            aggregateMetadata = routeSpec.aggregateMetadata,
                            stateAggregateFactory = stateAggregateFactory,
                            eventStore = eventStore,
                            snapshotRepository = snapshotRepository,
                            exceptionHandler = exceptionHandler,
                        )
                    )
                }

                is BatchRegenerateSnapshotRouteSpec -> {
                    routerFunctionBuilder.route(
                        requestPredicate,
                        BatchRegenerateSnapshotHandlerFunction(
                            aggregateMetadata = routeSpec.aggregateMetadata,
                            stateAggregateFactory = stateAggregateFactory,
                            eventStore = eventStore,
                            snapshotRepository = snapshotRepository,
                            exceptionHandler = exceptionHandler,
                        )
                    )
                }

                is RegenerateStateEventRouteSpec -> {
                    routerFunctionBuilder.route(
                        requestPredicate,
                        RegenerateStateEventFunction(
                            aggregateMetadata = routeSpec.aggregateMetadata,
                            eventStore = eventStore,
                            stateEventCompensator = stateEventCompensator,
                            exceptionHandler = exceptionHandler,
                        )
                    )
                }

                is DomainEventCompensateRouteSpec -> {
                    routerFunctionBuilder.route(
                        requestPredicate,
                        DomainEventCompensateHandlerFunction(
                            aggregateMetadata = routeSpec.aggregateMetadata,
                            eventCompensator = domainEventCompensator,
                            exceptionHandler = exceptionHandler,
                        )
                    )
                }

                is StateEventCompensateRouteSpec -> {
                    routerFunctionBuilder.route(
                        requestPredicate,
                        StateEventCompensateHandlerFunction(
                            aggregateMetadata = routeSpec.aggregateMetadata,
                            eventCompensator = stateEventCompensator,
                            exceptionHandler = exceptionHandler,
                        )
                    )
                }

                is DefaultDeleteAggregateRouteSpec -> {
                    routerFunctionBuilder.route(
                        requestPredicate,
                        DeleteAggregateHandlerFunction(
                            aggregateMetadata = routeSpec.aggregateMetadata,
                            commandGateway = commandGateway,
                            exceptionHandler = exceptionHandler,
                        )
                    )
                }

                is CommandRouteSpec -> {
                    @Suppress("UNCHECKED_CAST")
                    routerFunctionBuilder.route(
                        requestPredicate,
                        CommandHandlerFunction(
                            aggregateMetadata = routeSpec.aggregateMetadata,
                            commandRouteMetadata = routeSpec.commandRouteMetadata as CommandRouteMetadata<out Any>,
                            commandGateway = commandGateway,
                            exceptionHandler = exceptionHandler,
                        )
                    )
                }

                else -> {
                    throw UnsupportedOperationException("Unsupported routeSpec: $routeSpec")
                }
            }
        }
        return routerFunctionBuilder.build()
    }
}
