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
 * The command dispatcher of the aggregates of one bounded context, fed by one receiver (design X7).
 *
 * Each command is handled with the [AggregateMetadata] of its aggregate. Commands of one aggregate ID run one at a
 * time in arrival order in that ID's mailbox; different aggregate IDs run in parallel on the runtime's shared workers
 * ([me.ahoo.wow.execution.KeyedExecutor]).
 *
 * @property aggregateMetadata The metadata of the dispatched aggregates, all of one bounded context.
 * @param name The name of this dispatcher.
 * @param messageFlux The flux of command exchanges to process.
 * @param commandHandler The command handler for processing commands.
 * @param messageReadiness Completion of asynchronous message-source setup when
 * this dispatcher is registered directly with a runtime.
 * @param processingAdmission Explicit transport-processing gate opened by
 * [start].
 * @param processingQuiescence Logical transport gate closed by [quiesce].
 * @param metrics Instance-scoped metrics recorder for dispatcher operations.
 */
class AggregateCommandDispatcher(
    val aggregateMetadata: List<AggregateMetadata<*, *>>,
    override val name: String =
        "${aggregateMetadata.first().contextName}-${AggregateCommandDispatcher::class.simpleName!!}",
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
    init {
        require(aggregateMetadata.isNotEmpty()) {
            "aggregateMetadata must not be empty."
        }
        require(aggregateMetadata.map { it.contextName }.distinct().size == 1) {
            "aggregateMetadata must belong to one bounded context."
        }
    }

    private val metadataByAggregateName: Map<String, AggregateMetadata<*, *>> =
        aggregateMetadata.associateBy { it.aggregateName }

    override val namedAggregates: Set<NamedAggregate> =
        aggregateMetadata.mapTo(LinkedHashSet()) { it.namedAggregate }

    /**
     * Handles a single command exchange with the metadata of its aggregate.
     *
     * @param exchange The command exchange to handle.
     * @return A Mono that completes when the exchange has been processed; an error when the command's aggregate is
     * not one of [aggregateMetadata] (its receiver subscribes only their topics).
     */
    override fun handleExchange(exchange: ServerCommandExchange<*>): Mono<Void> {
        val metadata = metadataByAggregateName[exchange.message.aggregateName]
            ?: return Mono.error(
                IllegalStateException(
                    "[$name] Received a command of aggregate[${exchange.message.aggregateName}], " +
                        "which is not one of $namedAggregates.",
                ),
            )
        return commandHandler.handle(exchange, metadata)
    }

    /** Commands of one aggregate run in order: the mailbox key is the aggregate ID. */
    override fun ServerCommandExchange<*>.mailboxKey(): Any = message.aggregateId.id

    /** The per-aggregate dispatcher name 9.2 reported, kept as the metric tag. */
    override fun metricProcessorName(namedAggregate: NamedAggregate): String =
        "${namedAggregate.aggregateName}-${AggregateCommandDispatcher::class.simpleName!!}"
}
