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
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.dispatcher.AbstractMessageDispatcher
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import reactor.core.publisher.GroupedFlux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration

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
    private val aggregateTtl: Duration,
    private val commandBus: CommandBus,
    private val aggregateProcessorFactory: AggregateProcessorFactory,
    private val commandHandler: CommandHandler,
    private val serviceProvider: ServiceProvider,
) : AbstractMessageDispatcher<Void>() {

    private companion object {
        private const val CREATE_AGGREGATE_KEY = -1
        private fun ServerCommandExchange<*>.asGroupKey(mod: Int = Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE): Int {
            if (message.isCreate) {
                return CREATE_AGGREGATE_KEY
            }
            return message.aggregateId.hashCode() % mod
        }

        private fun Int.isCreateKey(): Boolean {
            return this == CREATE_AGGREGATE_KEY
        }
    }

    private val scheduler: Scheduler = Schedulers.boundedElastic()

    override fun start() {
        commandBus
            .receive(topics)
            .writeReceiverGroup(name)
            .groupBy { it.asGroupKey() }
            .flatMap({ handleGroupedCommand(it) }, Int.MAX_VALUE)
            .subscribe(this)
    }

    /**
     * One AggregateId binds one Worker(Thread).
     * One Worker can be bound by multiple aggregateIds.
     * Workers have aggregate ID affinity.
     *
     */
    private fun handleGroupedCommand(grouped: GroupedFlux<Int, ServerCommandExchange<Any>>): Mono<Void> {
        if (grouped.key().isCreateKey()) {
            return grouped.publishOn(scheduler).flatMap({ handleCommand(it) }, Int.MAX_VALUE).then()
        }
        return grouped.publishOn(scheduler)
            .concatMap { handleCommand(it) }.then()
    }

    private fun handleCommand(commandExchange: ServerCommandExchange<Any>): Mono<Void> {
        val aggregateId = commandExchange.message.aggregateId

        @Suppress("UNCHECKED_CAST")
        val aggregateType: Class<Any> =
            MetadataSearcher.namedAggregateType[aggregateId.namedAggregate]!! as Class<Any>
        val aggregateMetadata = aggregateType.asAggregateMetadata<Any, Any>()
        val aggregateProcessor = aggregateProcessorFactory.create(aggregateId, aggregateMetadata)
        commandExchange.serviceProvider = serviceProvider
        commandExchange.aggregateProcessor = aggregateProcessor
        return commandHandler.handle(commandExchange)
    }

}
