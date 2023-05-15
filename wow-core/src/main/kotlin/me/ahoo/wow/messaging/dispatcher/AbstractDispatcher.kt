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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.metrics.Metrics.writeMetricsSubscriber
import me.ahoo.wow.serialization.asJsonString
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux

abstract class AbstractDispatcher<T : Any> : MessageDispatcher {
    companion object {
        private val log = LoggerFactory.getLogger(AbstractDispatcher::class.java)
    }

    /**
     * must be [me.ahoo.wow.modeling.MaterializedNamedAggregate]
     */
    abstract val namedAggregates: Set<NamedAggregate>

    abstract fun receiveMessage(namedAggregate: NamedAggregate): Flux<T>
    abstract fun newAggregateDispatcher(namedAggregate: NamedAggregate, messageFlux: Flux<T>): MessageDispatcher
    protected val aggregateDispatchers = lazy {
        namedAggregates
            .map {
                val messageFlux = receiveMessage(it)
                    .writeReceiverGroup(name)
                    .writeMetricsSubscriber(name)
                newAggregateDispatcher(it, messageFlux)
            }
    }

    override fun run() {
        if (log.isInfoEnabled) {
            log.info("[$name] Run subscribe to namedAggregates:${namedAggregates.asJsonString()}.")
        }
        if (namedAggregates.isEmpty()) {
            if (log.isWarnEnabled) {
                log.warn("[$name] Ignore start because namedAggregates is empty.")
            }
            return
        }
        aggregateDispatchers.value.forEach { it.run() }
    }

    override fun close() {
        if (log.isInfoEnabled) {
            log.info("[$name] Close.")
        }
        aggregateDispatchers.value.forEach { it.close() }
    }
}
