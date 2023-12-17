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

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.metrics.Metrics
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.GroupedFlux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

abstract class AggregateMessageDispatcher<T : MessageExchange<*, *>> : MessageDispatcher, NamedAggregateDecorator,
    SafeSubscriber<Void>() {
    companion object {
        private val log = LoggerFactory.getLogger(AggregateMessageDispatcher::class.java)
    }

    abstract val parallelism: Int
    abstract val scheduler: Scheduler
    abstract val messageFlux: Flux<T>
    override fun run() {
        if (log.isInfoEnabled) {
            log.info("[$name] Run subscribe to $namedAggregate.")
        }
        messageFlux
            .groupBy { it.toGroupKey() }
            .flatMap({
                handleGroupedExchange(it)
            }, Int.MAX_VALUE, Int.MAX_VALUE)
            .subscribe(this)
    }

    abstract fun T.toGroupKey(): Int

    private fun handleGroupedExchange(grouped: GroupedFlux<Int, T>): Mono<Void> {
        return grouped.name(Wow.WOW_PREFIX + "dispatcher")
            .tag("dispatcher", name)
            .tag(Metrics.AGGREGATE_KEY, namedAggregate.aggregateName)
            .tag("group.key", grouped.key().toString())
            .metrics()
            .publishOn(scheduler)
            .concatMap { handleExchange(it) }
            .then()
    }

    abstract fun handleExchange(exchange: T): Mono<Void>

    override fun close() {
        if (log.isInfoEnabled) {
            log.info("[$name] Close.")
        }
        cancel()
    }
}
