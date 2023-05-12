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

package me.ahoo.wow.command

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.infra.Decorator.Companion.getDelegate
import me.ahoo.wow.metrics.AbstractMetricDecorator.Companion.tagSource
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.materialize
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class LocalFirstCommandBus(
    private val distributedCommandBus: CommandBus,
    private val localCommandBus: CommandBus = InMemoryCommandBus().metrizable(),
    private val localAggregates: Set<NamedAggregate> = MetadataSearcher.namedAggregateType.keys.toSet(),
) : CommandBus {
    private val localName = localCommandBus.getDelegate().javaClass.name
    private val distributedName = distributedCommandBus.getDelegate().javaClass.name
    override fun send(message: CommandMessage<*>): Mono<Void> {
        if (localAggregates.contains(message.materialize())) {
            return localCommandBus.send(message).tagSource(localName)
        }
        return distributedCommandBus.send(message).tagSource(distributedName)
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<Any>> {
        val localFlux = localCommandBus.receive(namedAggregates).tagSource(localName)
        val distributedFlux =
            distributedCommandBus.receive(namedAggregates).tagSource(distributedName)
        return Flux.merge(localFlux, distributedFlux)
    }
}
