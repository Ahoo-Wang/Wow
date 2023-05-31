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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.exception.asServerResponse
import me.ahoo.wow.webflux.route.CommandParser.getTenantId
import me.ahoo.wow.webflux.route.EventAggregateState.Companion.trace
import me.ahoo.wow.webflux.route.appender.RoutePaths
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class AggregateTracingHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val eventStore: EventStore,
    private val exceptionHandler: ExceptionHandler
) : HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantId(aggregateMetadata)
        val id = request.pathVariable(RoutePaths.ID_KEY)
        val aggregateId = aggregateMetadata.asAggregateId(id = id, tenantId = tenantId)

        return eventStore
            .load(
                aggregateId = aggregateId,
            ).collectList()
            .map {
                aggregateMetadata.state.trace(it)
            }.asServerResponse(exceptionHandler)
    }
}

data class EventAggregateState<S : Any>(val eventStream: DomainEventStream, val state: StateAggregate<S>) {
    companion object {
        private fun <S : Any> StateAggregateMetadata<S>.sourcing(
            aggregateId: AggregateId,
            eventStreams: List<DomainEventStream>
        ): EventAggregateState<S> {
            val stateAggregate = ConstructorStateAggregateFactory.createStateAggregate(this, aggregateId)
            eventStreams.forEach {
                stateAggregate.onSourcing(it)
            }
            return EventAggregateState(eventStream = eventStreams.last(), state = stateAggregate)
        }

        fun <S : Any> StateAggregateMetadata<S>.trace(
            eventStreams: List<DomainEventStream>
        ): List<EventAggregateState<S>> {
            if (eventStreams.isEmpty()) {
                return listOf()
            }
            val aggregateId = eventStreams.first().aggregateId
            return List(eventStreams.size) { index ->
                sourcing(aggregateId, eventStreams.take(index + 1))
            }
        }
    }
}
