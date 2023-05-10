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
package me.ahoo.wow.modeling.command

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.asRequiredAggregateType
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.dispatcher.AbstractDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.metrics.Metrics.writeMetricsSubscriber
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import me.ahoo.wow.scheduler.AggregateSchedulerRegistrar
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler

/**
 * Aggregate Command Dispatcher .

 * @author ahoo wang
 */
@Suppress("LongParameterList")
class CommandDispatcher(
    override val name: String = CommandDispatcher::class.simpleName!!,
    val parallelism: MessageParallelism = MessageParallelism.DEFAULT,
    override val namedAggregates: Set<NamedAggregate> = MetadataSearcher.namedAggregateType.keys.toSet(),
    private val commandBus: CommandBus,
    private val aggregateProcessorFactory: AggregateProcessorFactory,
    private val commandHandler: CommandHandler,
    private val serviceProvider: ServiceProvider,
    private val schedulerSupplier: (NamedAggregate) -> Scheduler =
        AggregateSchedulerRegistrar.DEFAULT_SCHEDULER_SUPPLIER
) : AbstractDispatcher<ServerCommandExchange<Any>>() {
    override fun receiveMessage(namedAggregate: NamedAggregate): Flux<ServerCommandExchange<Any>> {
        return commandBus
            .receive(setOf(namedAggregate))
            .writeReceiverGroup(name)
            .writeMetricsSubscriber(name)
    }

    override fun newAggregateDispatcher(
        namedAggregate: NamedAggregate,
        messageFlux: Flux<ServerCommandExchange<Any>>
    ): MessageDispatcher {
        val aggregateMetadata = namedAggregate
            .asRequiredAggregateType<Any>()
            .asAggregateMetadata<Any, Any>()
        return AggregateCommandDispatcher(
            aggregateMetadata = aggregateMetadata,
            scheduler = schedulerSupplier(namedAggregate),
            messageFlux = messageFlux,
            parallelism = parallelism,
            aggregateProcessorFactory = aggregateProcessorFactory,
            commandHandler = commandHandler,
            serviceProvider = serviceProvider
        )
    }
}
