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
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.dispatcher.AbstractMessageDispatcher
import me.ahoo.wow.messaging.dispatcher.AggregateGroupKey
import me.ahoo.wow.messaging.dispatcher.AggregateGroupKey.Companion.asGroupKey
import me.ahoo.wow.messaging.dispatcher.AggregateGroupKey.Companion.isCreate
import me.ahoo.wow.metrics.Metrics
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.materialize
import reactor.core.publisher.Flux
import reactor.core.publisher.GroupedFlux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

/**
 * Aggregate Command Dispatcher Grouped by NamedAggregate.
 * @author ahoo wang
 */
@Suppress("LongParameterList")
class AggregateDispatcher<C : Any, S : Any>(
    val aggregateMetadata: AggregateMetadata<C, S>,
    val scheduler: Scheduler,
    private val commandFlux: Flux<ServerCommandExchange<Any>>,
    override val name: String = AggregateDispatcher::class.simpleName!!,
    private val aggregateProcessorFactory: AggregateProcessorFactory,
    private val commandHandler: CommandHandler,
    private val serviceProvider: ServiceProvider
) : AbstractMessageDispatcher<Void>() {

    override val topics: Set<NamedAggregate>
        get() = setOf(aggregateMetadata.materialize())

    override fun start() {
        commandFlux
            .groupBy { it.message.asGroupKey() }
            .flatMap({
                handleGroupedCommand(it)
            }, Int.MAX_VALUE, Int.MAX_VALUE)
            .subscribe(this)
    }

    /**
     * One AggregateId binds one Worker(Thread).
     * One Worker can be bound by multiple aggregateIds.
     * Workers have aggregate ID affinity.
     *
     */
    private fun handleGroupedCommand(
        grouped: GroupedFlux<AggregateGroupKey, ServerCommandExchange<Any>>
    ): Mono<Void> {
        if (grouped.key().isCreate) {
            return grouped
                .name(Wow.WOW_PREFIX + "command.create")
                .tag(Metrics.AGGREGATE_KEY, aggregateMetadata.aggregateName)
                .metrics()
                .parallel().runOn(scheduler)
                .flatMap { handleCommand(it) }
                .then()
        }
        return grouped
            .name(Wow.WOW_PREFIX + "command.update")
            .tag(Metrics.AGGREGATE_KEY, aggregateMetadata.aggregateName)
            .metrics()
            .publishOn(scheduler)
            .concatMap { handleCommand(it) }
            .then()
    }

    private fun handleCommand(commandExchange: ServerCommandExchange<Any>): Mono<Void> {
        val aggregateId = commandExchange.message.aggregateId
        val aggregateProcessor = aggregateProcessorFactory.create(aggregateId, aggregateMetadata)
        commandExchange.serviceProvider = serviceProvider
        @Suppress("UNCHECKED_CAST")
        commandExchange.aggregateProcessor = aggregateProcessor as AggregateProcessor<Any>
        return commandHandler.handle(commandExchange)
    }
}
