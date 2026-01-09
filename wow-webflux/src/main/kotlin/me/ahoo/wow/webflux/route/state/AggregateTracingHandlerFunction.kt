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
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.openapi.aggregate.state.AggregateTracingRouteSpec
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.deepCopy
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.getTenantIdOrDefault
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class AggregateTracingHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val exceptionHandler: RequestExceptionHandler
) : HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantIdOrDefault(aggregateMetadata)
        val id = request.pathVariable(MessageRecords.ID)
        val aggregateId = aggregateMetadata.aggregateId(id = id, tenantId = tenantId)
        return eventStore
            .load(
                aggregateId = aggregateId,
            ).collectList()
            .map {
                aggregateMetadata.state.trace(stateAggregateFactory, it)
            }.toServerResponse(request, exceptionHandler)
    }

    companion object {

        private fun <S : Any> StateAggregateMetadata<S>.sourcing(
            aggregateId: AggregateId,
            stateAggregateFactory: StateAggregateFactory,
            eventStreams: List<DomainEventStream>
        ): StateEvent<S> {
            val stateAggregate = stateAggregateFactory.create(this, aggregateId)
            eventStreams.forEach {
                stateAggregate.onSourcing(it)
            }
            return eventStreams.last().toStateEvent(stateAggregate)
        }

        fun <S : Any> StateAggregateMetadata<S>.trace(
            stateAggregateFactory: StateAggregateFactory,
            eventStreams: List<DomainEventStream>
        ): List<StateEvent<S>> {
            if (eventStreams.isEmpty()) {
                return listOf()
            }
            val aggregateId = eventStreams.first().aggregateId
            return List(eventStreams.size) { index ->
                sourcing(
                    aggregateId,
                    stateAggregateFactory,
                    eventStreams.take(index + 1).map {
                        it.deepCopy(DomainEventStream::class.java)
                    }
                )
            }
        }
    }
}

class AggregateTracingHandlerFunctionFactory(
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val exceptionHandler: RequestExceptionHandler
) : RouteHandlerFunctionFactory<AggregateTracingRouteSpec> {
    override val supportedSpec: Class<AggregateTracingRouteSpec>
        get() = AggregateTracingRouteSpec::class.java

    override fun create(spec: AggregateTracingRouteSpec): HandlerFunction<ServerResponse> {
        return AggregateTracingHandlerFunction(
            spec.aggregateMetadata,
            stateAggregateFactory,
            eventStore,
            exceptionHandler
        )
    }
}
