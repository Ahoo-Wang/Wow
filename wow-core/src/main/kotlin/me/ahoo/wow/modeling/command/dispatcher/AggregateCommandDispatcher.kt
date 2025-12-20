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
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.dispatcher.AggregateDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.dispatcher.MessageParallelism.toGroupKey
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

/**
 * Aggregate command dispatcher grouped by named aggregate.
 *
 * This dispatcher manages command processing for a specific named aggregate, ensuring proper
 * parallelism and thread affinity. Each aggregate ID is bound to one worker thread, but one
 * worker can handle multiple aggregate IDs, providing efficient resource utilization.
 *
 * Key characteristics:
 * - One AggregateId binds to one Worker (Thread)
 * - One Worker can be bound by multiple aggregateIds
 * - Workers have aggregate ID affinity for consistent processing
 *
 * @param C The type of the command aggregate root.
 * @param S The type of the state aggregate.
 * @property aggregateMetadata The metadata for the aggregate being dispatched.
 * @param parallelism The level of parallelism for message processing.
 * @param scheduler The scheduler for handling messages.
 * @param messageFlux The flux of command exchanges to process.
 * @param name The name of this dispatcher.
 * @param aggregateProcessorFactory Factory for creating aggregate processors.
 * @param commandHandler The command handler for processing commands.
 * @param serviceProvider Provider for accessing services.
 */
@Suppress("LongParameterList")
class AggregateCommandDispatcher<C : Any, S : Any>(
    val aggregateMetadata: AggregateMetadata<C, S>,
    override val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    override val scheduler: Scheduler,
    override val messageFlux: Flux<ServerCommandExchange<*>>,
    override val name: String =
        "${aggregateMetadata.aggregateName}-${AggregateCommandDispatcher::class.simpleName!!}",
    private val aggregateProcessorFactory: AggregateProcessorFactory,
    private val commandHandler: CommandHandler,
    private val serviceProvider: ServiceProvider
) : AggregateDispatcher<ServerCommandExchange<*>>() {
    override val namedAggregate: NamedAggregate
        get() = aggregateMetadata.namedAggregate

    /**
     * Handles a single command exchange by setting up the processing context and delegating to the command handler.
     *
     * @param exchange The command exchange to handle.
     * @return A Mono that completes when the exchange has been processed.
     */
    override fun handleExchange(exchange: ServerCommandExchange<*>): Mono<Void> {
        val aggregateId = exchange.message.aggregateId
        val aggregateProcessor =
            aggregateProcessorFactory.create(aggregateId, aggregateMetadata)
        exchange.setServiceProvider(serviceProvider)
            .setAggregateProcessor(aggregateProcessor)
        return commandHandler.handle(exchange)
    }

    /**
     * Generates a group key for the command exchange to ensure proper parallelism and ordering.
     *
     * @return The group key for this exchange.
     */
    override fun ServerCommandExchange<*>.toGroupKey(): Int = message.toGroupKey(parallelism)
}
