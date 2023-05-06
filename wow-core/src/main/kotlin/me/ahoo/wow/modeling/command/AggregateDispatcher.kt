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

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.asRequiredAggregateType
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.dispatcher.AbstractMessageDispatcher
import me.ahoo.wow.messaging.dispatcher.AggregateGroupKey
import me.ahoo.wow.messaging.dispatcher.AggregateGroupKey.Companion.asGroupKey
import me.ahoo.wow.messaging.dispatcher.AggregateGroupKey.Companion.isCreate
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.scheduler.AggregateSchedulerRegistrar
import reactor.core.publisher.GroupedFlux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.util.concurrent.Queues

/**
 * Aggregate Command Dispatcher .

 * @author ahoo wang
 */
@Suppress("LongParameterList")
class AggregateDispatcher(
    /**
     * named like `AggregateDispatcher`
     */
    override val name: String = "AggregateDispatcher",
    override val topics: Set<NamedAggregate> = MetadataSearcher.namedAggregateType.keys.toSet(),
    private val commandBus: CommandBus,
    private val aggregateProcessorFactory: AggregateProcessorFactory,
    private val commandHandler: CommandHandler,
    private val serviceProvider: ServiceProvider
) : AbstractMessageDispatcher<Void>() {

    override fun start() {
        commandBus
            .receive(topics)
            .writeReceiverGroup(name)
            .name(Wow.WOW_PREFIX + "commands")
            .tag("dispatcher", name)
            .metrics()
            .groupBy { it.message.aggregateId.namedAggregate.materialize() }
            .flatMap({ handle(it) }, Int.MAX_VALUE, Queues.SMALL_BUFFER_SIZE)
            .subscribe(this)
    }

    private fun handle(grouped: GroupedFlux<MaterializedNamedAggregate, ServerCommandExchange<Any>>): Mono<Void> {
        val scheduler = requireNotNull(AggregateSchedulerRegistrar.getOrInitialize(grouped.key()))
        return grouped.groupBy { it.message.asGroupKey() }
            .flatMap({ handleGroupedCommand(grouped.key(), scheduler, it) }, Int.MAX_VALUE, Queues.SMALL_BUFFER_SIZE)
            .then()
    }

    /**
     * One AggregateId binds one Worker(Thread).
     * One Worker can be bound by multiple aggregateIds.
     * Workers have aggregate ID affinity.
     *
     */
    private fun handleGroupedCommand(
        namedAggregate: NamedAggregate,
        scheduler: Scheduler,
        grouped: GroupedFlux<AggregateGroupKey, ServerCommandExchange<Any>>
    ): Mono<Void> {
        if (grouped.key().isCreate) {
            return grouped
                .name(Wow.WOW_PREFIX + "commands.create")
                .tag("aggregate", namedAggregate.aggregateName)
                .metrics()
                .parallel(Queues.SMALL_BUFFER_SIZE)
                .runOn(scheduler)
                .flatMap { handleCommand(it) }
                .then()
        }
        return grouped
            .metrics()
            .publishOn(scheduler)
            .concatMap({ handleCommand(it) }, Queues.SMALL_BUFFER_SIZE)
            .then()
    }

    private fun handleCommand(commandExchange: ServerCommandExchange<Any>): Mono<Void> {
        val aggregateId = commandExchange.message.aggregateId

        val aggregateMetadata = aggregateId.namedAggregate
            .asRequiredAggregateType<Any>()
            .asAggregateMetadata<Any, Any>()
        val aggregateProcessor = aggregateProcessorFactory.create(aggregateId, aggregateMetadata)
        commandExchange.serviceProvider = serviceProvider
        commandExchange.aggregateProcessor = aggregateProcessor
        return commandHandler.handle(commandExchange)
    }
}
