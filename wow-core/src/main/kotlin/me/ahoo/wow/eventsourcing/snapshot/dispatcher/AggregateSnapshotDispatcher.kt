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

package me.ahoo.wow.eventsourcing.snapshot.dispatcher

import me.ahoo.wow.api.messaging.processor.ProcessorInfo
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.dispatcher.AggregateDispatcher
import me.ahoo.wow.messaging.dispatcher.toMailboxKey
import me.ahoo.wow.metrics.WowMetrics
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Dispatcher for handling snapshot operations on the state events of the aggregates of one bounded context.
 * Routes state event exchanges to the snapshot handler for processing.
 *
 * @param namedAggregates the aggregates this dispatcher handles, all of one bounded context
 * @param name the name of this dispatcher (default: contextName-AggregateSnapshotDispatcher)
 * @param messageFlux the flux of state event exchanges to process
 * @param snapshotHandler the handler responsible for creating and storing snapshots
 * @param messageReadiness completion of asynchronous message-source setup when
 * this dispatcher is registered directly with a runtime
 * @param processingAdmission explicit transport-processing gate opened by
 * [start]
 * @param processingQuiescence logical transport gate closed by [quiesce]
 * @param metrics instance-scoped metrics recorder for dispatcher operations
 */
class AggregateSnapshotDispatcher(
    override val namedAggregates: Set<NamedAggregate>,
    override val name: String =
        "${namedAggregates.first().contextName}-${AggregateSnapshotDispatcher::class.simpleName!!}",
    override val messageFlux: Flux<StateEventExchange<*>>,
    private val snapshotHandler: SnapshotHandler,
    messageReadiness: Mono<Void> = Mono.empty(),
    processingAdmission: () -> Unit = {},
    processingQuiescence: () -> Unit = {},
    metrics: WowMetrics = WowMetrics.NONE,
) : AggregateDispatcher<StateEventExchange<*>>(
    messageReadiness = messageReadiness,
    processingAdmission = processingAdmission,
    processingQuiescence = processingQuiescence,
    metrics = metrics,
),
    ProcessorInfo {
    /**
     * The context name of the aggregate.
     */
    override val contextName: String
        get() = namedAggregates.first().contextName

    /**
     * The processor name, set to SNAPSHOT_PROCESSOR_NAME.
     */
    override val processorName: String
        get() = SNAPSHOT_PROCESSOR_NAME

    /**
     * Handles a state event exchange by setting the snapshot function and delegating to the snapshot handler.
     *
     * @param exchange the state event exchange to handle
     * @return a Mono that completes when handling is done
     */
    override fun handleExchange(exchange: StateEventExchange<*>): Mono<Void> {
        exchange.setFunction(SNAPSHOT_FUNCTION)
        return snapshotHandler.handle(exchange)
    }

    /**
     * State events of one aggregate are snapshotted in order: the mailbox key is the aggregate ID ([toMailboxKey]:
     * bounded context, aggregate name and ID, not the tenant, as in 9.2), since one dispatcher serves several aggregates of a context and
     * an ID (for example one derived by a saga) can be shared across aggregate types.
     */
    override fun StateEventExchange<*>.mailboxKey(): Any = message.aggregateId.toMailboxKey()

    /** The per-aggregate dispatcher name 9.2 reported, kept as the metric tag. */
    override fun metricProcessorName(namedAggregate: NamedAggregate): String =
        "${namedAggregate.aggregateName}-${AggregateSnapshotDispatcher::class.simpleName!!}"
}
