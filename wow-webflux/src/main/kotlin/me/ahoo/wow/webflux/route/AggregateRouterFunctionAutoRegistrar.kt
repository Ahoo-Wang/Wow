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

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.event.EventCompensator
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotSink
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.route.appender.AggregateTracingRouteAppender
import me.ahoo.wow.webflux.route.appender.BatchRegenerateSnapshotRouteAppender
import me.ahoo.wow.webflux.route.appender.CommandRouteAppender
import me.ahoo.wow.webflux.route.appender.DeleteAggregateRouteAppender
import me.ahoo.wow.webflux.route.appender.EventCompensateRouteAppender
import me.ahoo.wow.webflux.route.appender.LoadAggregateRouteAppender
import me.ahoo.wow.webflux.route.appender.RegenerateSnapshotRouteAppender
import me.ahoo.wow.webflux.route.appender.SnapshotSinkRouteAppender
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * [org.springframework.web.reactive.function.server.support.RouterFunctionMapping]
 */
@Suppress("UNCHECKED_CAST", "LongParameterList")
class AggregateRouterFunctionAutoRegistrar(
    private val currentContext: NamedBoundedContext,
    private val commandGateway: CommandGateway,
    private val stateAggregateRepository: StateAggregateRepository,
    private val snapshotRepository: SnapshotRepository,
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val snapshotSink: SnapshotSink,
    private val eventCompensator: EventCompensator,
    private val exceptionHandler: ExceptionHandler
) {
    val routerFunction: RouterFunction<ServerResponse> by lazy {
        buildRouterFunction()
    }

    @Suppress("LongMethod")
    private fun buildRouterFunction(): RouterFunction<ServerResponse> {
        check(MetadataSearcher.namedAggregateType.isNotEmpty()) {
            "No Typed Aggregate found!"
        }

        val routerFunctionBuilder = org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route()
        MetadataSearcher.namedAggregateType.forEach { aggregateEntry ->
            val aggregateType = aggregateEntry.value as Class<Any>
            val aggregateMetadata = aggregateType.asAggregateMetadata<Any, Any>()
            LoadAggregateRouteAppender(
                currentContext = currentContext,
                aggregateMetadata = aggregateMetadata,
                routerFunctionBuilder = routerFunctionBuilder,
                stateAggregateRepository = stateAggregateRepository,
                exceptionHandler = exceptionHandler,
            ).append()
            RegenerateSnapshotRouteAppender(
                currentContext = currentContext,
                aggregateMetadata = aggregateMetadata,
                routerFunctionBuilder = routerFunctionBuilder,
                snapshotRepository = snapshotRepository,
                stateAggregateFactory = stateAggregateFactory,
                eventStore = eventStore,
                exceptionHandler = exceptionHandler,
            ).append()
            BatchRegenerateSnapshotRouteAppender(
                currentContext = currentContext,
                aggregateMetadata = aggregateMetadata,
                routerFunctionBuilder = routerFunctionBuilder,
                snapshotRepository = snapshotRepository,
                stateAggregateFactory = stateAggregateFactory,
                eventStore = eventStore,
                exceptionHandler = exceptionHandler,
            ).append()
            SnapshotSinkRouteAppender(
                currentContext = currentContext,
                aggregateMetadata = aggregateMetadata,
                routerFunctionBuilder = routerFunctionBuilder,
                snapshotRepository = snapshotRepository,
                stateAggregateFactory = stateAggregateFactory,
                eventStore = eventStore,
                snapshotSink = snapshotSink,
                exceptionHandler = exceptionHandler,
            ).append()
            if (!aggregateMetadata.command.registeredDeleteAggregate) {
                DeleteAggregateRouteAppender(
                    currentContext = currentContext,
                    aggregateMetadata = aggregateMetadata,
                    routerFunctionBuilder = routerFunctionBuilder,
                    commandGateway = commandGateway,
                    exceptionHandler = exceptionHandler,
                ).append()
            }
            CommandRouteAppender(
                currentContext = currentContext,
                aggregateMetadata = aggregateMetadata,
                routerFunctionBuilder = routerFunctionBuilder,
                commandGateway = commandGateway,
                exceptionHandler = exceptionHandler,
            ).append()
            EventCompensateRouteAppender(
                currentContext = currentContext,
                aggregateMetadata = aggregateMetadata,
                routerFunctionBuilder = routerFunctionBuilder,
                eventCompensator = eventCompensator,
                exceptionHandler = exceptionHandler,
            ).append()
            AggregateTracingRouteAppender(
                currentContext = currentContext,
                aggregateMetadata = aggregateMetadata,
                routerFunctionBuilder = routerFunctionBuilder,
                eventStore = eventStore,
                exceptionHandler = exceptionHandler,
            ).append()
        }
        return routerFunctionBuilder.build()
    }
}
