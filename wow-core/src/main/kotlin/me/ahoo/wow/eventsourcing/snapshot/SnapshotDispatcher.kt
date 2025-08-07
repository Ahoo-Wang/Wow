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

package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.compensation.CompensationMatcher.match
import me.ahoo.wow.messaging.dispatcher.AbstractDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.handler.ExchangeAck.filterThenAck
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.metrics.Metrics.writeMetricsSubscriber
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.scheduler.DefaultAggregateSchedulerSupplier
import reactor.core.publisher.Flux

internal const val SNAPSHOT_PROCESSOR_NAME = "SnapshotDispatcher"

val SNAPSHOT_FUNCTION = FunctionInfoData(
    functionKind = FunctionKind.STATE_EVENT,
    contextName = Wow.WOW,
    processorName = SNAPSHOT_PROCESSOR_NAME,
    name = "save",
)

class SnapshotDispatcher(
    /**
     * named like `applicationName.SnapshotDispatcher`
     */
    override val name: String,
    override val namedAggregates: Set<NamedAggregate> = MetadataSearcher.namedAggregateType.keys.toSet(),
    private val snapshotHandler: SnapshotHandler,
    private val stateEventBus: StateEventBus,
    private val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    private val schedulerSupplier: AggregateSchedulerSupplier =
        DefaultAggregateSchedulerSupplier(SNAPSHOT_PROCESSOR_NAME)
) : AbstractDispatcher<StateEventExchange<*>>(), MessageDispatcher {

    override fun receiveMessage(namedAggregate: NamedAggregate): Flux<StateEventExchange<*>> {
        return stateEventBus
            .receive(setOf(namedAggregate))
            .writeReceiverGroup(name)
            .writeMetricsSubscriber(name)
            .filterThenAck {
                it.message.match(SNAPSHOT_FUNCTION)
            }
    }

    override fun newAggregateDispatcher(
        namedAggregate: NamedAggregate,
        messageFlux: Flux<StateEventExchange<*>>
    ): MessageDispatcher {
        return AggregateSnapshotDispatcher(
            snapshotHandler = snapshotHandler,
            namedAggregate = namedAggregate,
            parallelism = parallelism,
            scheduler = schedulerSupplier.getOrInitialize(namedAggregate),
            messageFlux = messageFlux,
        )
    }
}
