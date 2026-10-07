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
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Aggregate command dispatcher grouped by named aggregate.
 *
 * This dispatcher manages command processing for a specific named aggregate. Commands of one aggregate ID run one at
 * a time in arrival order in that ID's mailbox; different aggregate IDs run in parallel on the runtime's shared
 * workers ([me.ahoo.wow.execution.KeyedExecutor]).
 *
 * @param C The type of the command aggregate root.
 * @param S The type of the state aggregate.
 * @param name The name of this dispatcher.
 * @property aggregateMetadata The metadata for the aggregate being dispatched.
 * @param messageFlux The flux of command exchanges to process.
 * @param commandHandler The command handler for processing commands.
 * @param messageReadiness Completion of asynchronous message-source setup when
 * this dispatcher is registered directly with a runtime.
 * @param processingAdmission Explicit transport-processing gate opened by
 * [start].
 * @param processingQuiescence Logical transport gate closed by [quiesce].
 * @param metrics Instance-scoped metrics recorder for dispatcher operations.
 */
class AggregateCommandDispatcher<C : Any, S : Any>(
    override val name: String =
        "${aggregateMetadata.aggregateName}-${AggregateCommandDispatcher::class.simpleName!!}",
    val aggregateMetadata: AggregateMetadata<C, S>,
    override val messageFlux: Flux<ServerCommandExchange<*>>,
    private val commandHandler: CommandHandler,
    messageReadiness: Mono<Void> = Mono.empty(),
    processingAdmission: () -> Unit = {},
    processingQuiescence: () -> Unit = {},
    metrics: WowMetrics = WowMetrics.NONE,
) : AggregateDispatcher<ServerCommandExchange<*>>(
    messageReadiness = messageReadiness,
    processingAdmission = processingAdmission,
    processingQuiescence = processingQuiescence,
    metrics = metrics,
) {
    override val namedAggregate: NamedAggregate
        get() = aggregateMetadata.namedAggregate

    /**
     * Handles a single command exchange by setting up the processing context and delegating to the command handler.
     *
     * @param exchange The command exchange to handle.
     * @return A Mono that completes when the exchange has been processed.
     */
    override fun handleExchange(exchange: ServerCommandExchange<*>): Mono<Void> =
        commandHandler.handle(exchange, aggregateMetadata)

    /** Commands of one aggregate run in order: the mailbox key is the aggregate ID. */
    override fun ServerCommandExchange<*>.mailboxKey(): Any = message.aggregateId.id
}
