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
package me.ahoo.wow.event

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.BUSY_LOOPING_DURATION
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.publisher.Sinks.Many

class InMemoryDomainEventBus(
    private val sink: Many<EventStreamExchange> = Sinks.many()
        .multicast().onBackpressureBuffer()
) : LocalDomainEventBus {

    override fun sendExchange(exchange: EventStreamExchange): Mono<Void> {
        return Mono.fromRunnable {
            sink.emitNext(
                exchange,
                Sinks.EmitFailureHandler.busyLooping(BUSY_LOOPING_DURATION),
            )
        }
    }

    override fun send(message: DomainEventStream): Mono<Void> {
        return sendExchange(SimpleEventStreamExchange(message))
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> {
        return sink.asFlux()
            .filter { eventStream ->
                namedAggregates.any {
                    it.isSameAggregateName(eventStream.message)
                }
            }
    }
}
