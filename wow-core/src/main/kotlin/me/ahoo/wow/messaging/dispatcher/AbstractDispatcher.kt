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

package me.ahoo.wow.messaging.dispatcher

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.metrics.Metrics.writeMetricsSubscriber
import me.ahoo.wow.serialization.toJsonString
import reactor.core.publisher.Flux

/**
 * Abstract base class for message dispatchers that manage multiple aggregate dispatchers.
 *
 * This class coordinates the dispatching of messages to multiple named aggregates by
 * creating individual dispatchers for each aggregate and managing their lifecycle.
 *
 * @param T The type of message being dispatched
 */
abstract class AbstractDispatcher<T : Any> : MessageDispatcher {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * must be [me.ahoo.wow.modeling.MaterializedNamedAggregate]
     */
    abstract val namedAggregates: Set<NamedAggregate>

    /**
     * Creates a flux of messages for the specified named aggregate.
     *
     * @param namedAggregate The named aggregate to receive messages for
     * @return A flux of messages for the aggregate
     */
    abstract fun receiveMessage(namedAggregate: NamedAggregate): Flux<T>

    /**
     * Creates a new message dispatcher for a specific named aggregate.
     *
     * @param namedAggregate The named aggregate for the dispatcher
     * @param messageFlux The flux of messages for the aggregate
     * @return A new message dispatcher instance
     */
    abstract fun newAggregateDispatcher(
        namedAggregate: NamedAggregate,
        messageFlux: Flux<T>
    ): MessageDispatcher

    /**
     * Lazily initialized list of aggregate dispatchers, one for each named aggregate.
     *
     * Each dispatcher is created with a message flux that includes receiver group
     * and metrics context.
     */
    protected val aggregateDispatchers by lazy {
        namedAggregates
            .map {
                val messageFlux = receiveMessage(it)
                    .writeReceiverGroup(name)
                    .writeMetricsSubscriber(name)
                newAggregateDispatcher(it, messageFlux)
            }
    }

    /**
     * Starts the dispatcher by running all aggregate dispatchers.
     *
     * Logs the named aggregates being subscribed to and starts each individual
     * aggregate dispatcher. If no aggregates are configured, logs a warning and returns.
     */
    override fun run() {
        log.info {
            "[$name][${this.javaClass.simpleName}] Run subscribe to namedAggregates:${namedAggregates.toJsonString()}."
        }
        if (namedAggregates.isEmpty()) {
            log.warn {
                "[$name][${this.javaClass.simpleName}] Ignore start because namedAggregates is empty."
            }
            return
        }
        aggregateDispatchers.forEach { it.run() }
    }

    /**
     * Closes the dispatcher and all its aggregate dispatchers.
     *
     * Logs the closure and calls close on each aggregate dispatcher.
     */
    override fun close() {
        log.info {
            "[$name][${this.javaClass.simpleName}] Close."
        }
        aggregateDispatchers.forEach { it.close() }
    }
}
