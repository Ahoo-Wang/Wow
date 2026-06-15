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

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.EventStore.Companion.DEFAULT_TAIL_VERSION
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.openapi.aggregate.state.AggregateTracingRouteSpec
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.context.WowWebRequestContext
import me.ahoo.wow.webflux.route.policy.TracingPolicy
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class AggregateTracingHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val exceptionHandler: RequestExceptionHandler,
    private val tracingPolicy: TracingPolicy
) : HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val context = WowWebRequestContext.of(request, aggregateMetadata)
        val tracingRequest = tracingPolicy.request(request)
        return eventStore
            .load(
                aggregateId = context.aggregateId,
                tailVersion = tracingRequest.tailVersion ?: DEFAULT_TAIL_VERSION,
            )
            .let { eventStreams ->
                AggregateTracingReplay.trace(
                    stateAggregateMetadata = aggregateMetadata.state,
                    stateAggregateFactory = stateAggregateFactory,
                    eventStreams = eventStreams,
                    tracingRequest = tracingRequest,
                )
            }.toServerResponse(request, exceptionHandler)
    }
}

class AggregateTracingHandlerFunctionFactory(
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val exceptionHandler: RequestExceptionHandler,
    private val tracingPolicy: TracingPolicy
) : RouteHandlerFunctionFactory<AggregateTracingRouteSpec> {
    override val supportedSpec: Class<AggregateTracingRouteSpec>
        get() = AggregateTracingRouteSpec::class.java

    override fun create(spec: AggregateTracingRouteSpec): HandlerFunction<ServerResponse> {
        return AggregateTracingHandlerFunction(
            spec.aggregateMetadata,
            stateAggregateFactory,
            eventStore,
            exceptionHandler,
            tracingPolicy,
        )
    }
}
