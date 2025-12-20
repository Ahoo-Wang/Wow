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
package me.ahoo.wow.modeling.command.dispatcher

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.dispatcher.MainDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.handler.ExchangeAck.filterThenAck
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.scheduler.DefaultAggregateSchedulerSupplier
import reactor.core.publisher.Flux

/**
 * Command Dispatcher .

 * @author ahoo wang
 */
@Suppress("LongParameterList")
class CommandDispatcher(
    override val name: String = CommandDispatcher::class.simpleName!!,
    val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    override val namedAggregates: Set<NamedAggregate> = MetadataSearcher.localAggregates,
    private val commandBus: CommandBus,
    private val commandHandler: CommandHandler,
    private val schedulerSupplier: AggregateSchedulerSupplier =
        DefaultAggregateSchedulerSupplier("CommandDispatcher")
) : MainDispatcher<ServerCommandExchange<*>>() {
    override fun receiveMessage(namedAggregate: NamedAggregate): Flux<ServerCommandExchange<*>> {
        return commandBus
            .receive(setOf(namedAggregate))
            .filterThenAck {
                !it.message.isVoid
            }
    }

    override fun newAggregateDispatcher(
        namedAggregate: NamedAggregate,
        messageFlux: Flux<ServerCommandExchange<*>>
    ): MessageDispatcher {
        val aggregateMetadata = namedAggregate
            .requiredAggregateType<Any>()
            .aggregateMetadata<Any, Any>()
        return AggregateCommandDispatcher(
            aggregateMetadata = aggregateMetadata,
            scheduler = schedulerSupplier.getOrInitialize(namedAggregate),
            messageFlux = messageFlux,
            parallelism = parallelism,
            commandHandler = commandHandler,
        )
    }
}
