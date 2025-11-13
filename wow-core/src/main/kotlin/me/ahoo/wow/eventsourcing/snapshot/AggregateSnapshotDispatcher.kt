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

import me.ahoo.wow.api.messaging.processor.ProcessorInfo
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.dispatcher.AggregateMessageDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.dispatcher.MessageParallelism.toGroupKey
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

/**
 * Dispatcher for handling snapshot operations on state events for a specific aggregate.
 * Routes state event exchanges to the snapshot handler for processing.
 *
 * @param snapshotHandler the handler responsible for creating and storing snapshots
 * @param namedAggregate the named aggregate this dispatcher handles
 * @param name the name of this dispatcher (default: aggregateName-AggregateSnapshotDispatcher)
 * @param parallelism the number of parallel processing groups (default: MessageParallelism.DEFAULT_PARALLELISM)
 * @param scheduler the scheduler for processing messages
 * @param messageFlux the flux of state event exchanges to process
 */
class AggregateSnapshotDispatcher(
    private val snapshotHandler: SnapshotHandler,
    override val namedAggregate: NamedAggregate,
    override val name: String =
        "${namedAggregate.aggregateName}-${AggregateSnapshotDispatcher::class.simpleName!!}",
    override val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    override val scheduler: Scheduler,
    override val messageFlux: Flux<StateEventExchange<*>>
) : AggregateMessageDispatcher<StateEventExchange<*>>(),
    ProcessorInfo {
    /**
     * The context name of the aggregate.
     */
    override val contextName: String
        get() = namedAggregate.contextName

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
     * Computes the group key for parallel processing based on the message.
     *
     * @return the group key for this exchange
     */
    override fun StateEventExchange<*>.toGroupKey(): Int = message.toGroupKey(parallelism)
}
