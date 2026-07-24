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
import me.ahoo.wow.messaging.dispatcher.AggregateDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.dispatcher.MessageParallelism.toGroupKey
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

/**
 * Aggregate command dispatcher grouped by named aggregate.
 *
 * This dispatcher manages command processing for a specific named aggregate. Aggregate IDs
 * hash to ordering stripes; exchanges in one stripe enter the command handler sequentially
 * after a Scheduler handoff. Asynchronous handlers and stores may switch threads downstream.
 *
 * Key characteristics:
 * - One AggregateId consistently maps to one ordering stripe
 * - Multiple aggregate IDs may collide on the same stripe
 * - Each stripe preserves handler-entry order
 *
 * @param C The type of the command aggregate root.
 * @param S The type of the state aggregate.
 * @param name The name of this dispatcher.
 * @property aggregateMetadata The metadata for the aggregate being dispatched.
 * @param messageFlux The flux of command exchanges to process.
 * @param parallelism The number of ordering stripes, independent from Scheduler workers.
 * @param commandHandler The command handler for processing commands.
 * @param scheduler The scheduler for handling messages.
 */
class AggregateCommandDispatcher<C : Any, S : Any>(
    override val name: String =
        "${aggregateMetadata.aggregateName}-${AggregateCommandDispatcher::class.simpleName!!}",
    val aggregateMetadata: AggregateMetadata<C, S>,
    override val messageFlux: Flux<ServerCommandExchange<*>>,
    override val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    private val commandHandler: CommandHandler,
    override val scheduler: Scheduler,
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
        exchange.setAggregateMetadata(aggregateMetadata)
        return commandHandler.handle(exchange)
    }

    /**
     * Generates an ordering-stripe key for the command exchange.
     *
     * @return The group key for this exchange.
     */
    override fun ServerCommandExchange<*>.toGroupKey(): Int = message.toGroupKey(parallelism)
}
