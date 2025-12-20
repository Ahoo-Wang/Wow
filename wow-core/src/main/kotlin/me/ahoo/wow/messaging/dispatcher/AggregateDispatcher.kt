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
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.metrics.Metrics
import reactor.core.publisher.Flux
import reactor.core.publisher.GroupedFlux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Abstract dispatcher for handling message exchanges for a specific aggregate.
 *
 * This dispatcher groups message exchanges by a key for parallel processing,
 * applies metrics, and handles each exchange on a specified scheduler.
 *
 * @param T The type of message exchange being handled
 */
abstract class AggregateDispatcher<T : MessageExchange<*, *>> :
    SafeSubscriber<Void>(),
    MessageDispatcher,
    NamedAggregateDecorator {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * The level of parallelism for processing grouped exchanges.
     */
    abstract val parallelism: Int

    /**
     * The scheduler to use for processing message exchanges.
     */
    abstract val scheduler: Scheduler

    /**
     * The flux of message exchanges to be processed.
     */
    abstract val messageFlux: Flux<T>

    private val activityTaskCounter = AtomicInteger(0)

    /**
     * Starts the dispatcher by subscribing to the message flux.
     *
     * Groups messages by key for parallel processing and handles each group.
     * The result is subscribed to this dispatcher for error handling.
     */
    override fun start() {
        log.info {
            "[$name] Start subscribe to $namedAggregate."
        }
        messageFlux
            .groupBy { it.toGroupKey() }
            .flatMap({ grouped ->
                handleGroupedExchange(grouped)
            }, Int.MAX_VALUE, Int.MAX_VALUE)
            .subscribe(this)
    }

    /**
     * Converts a message exchange to a grouping key for parallel processing.
     *
     * @receiver The message exchange to group
     * @return An integer key for grouping exchanges
     */
    abstract fun T.toGroupKey(): Int

    /**
     * Handles a grouped flux of message exchanges.
     *
     * Applies metrics tagging, publishes on the specified scheduler,
     * and processes each exchange sequentially within the group.
     *
     * @param grouped The grouped flux of message exchanges
     * @return A Mono that completes when all exchanges in the group are handled
     */
    private fun handleGroupedExchange(grouped: GroupedFlux<Int, T>): Mono<Void> =
        grouped
            .name(Wow.WOW_PREFIX + "dispatcher")
            .tag("dispatcher", name)
            .tag(Metrics.AGGREGATE_KEY, namedAggregate.aggregateName)
            .tag("group.key", grouped.key().toString())
            .metrics()
            .publishOn(scheduler)
            .concatMap { exchange ->
                activityTaskCounter.incrementAndGet()
                handleExchange(exchange).doFinally {
                    activityTaskCounter.decrementAndGet()
                }
            }.then()

    /**
     * Handles a single message exchange.
     *
     * @param exchange The message exchange to handle
     * @return A Mono that completes when the exchange is handled
     */
    abstract fun handleExchange(exchange: T): Mono<Void>

    override fun stopGracefully(): Mono<Void> {
        log.info {
            "[$name] Stop gracefully. Active task count: ${activityTaskCounter.get()}"
        }
        // Cancel the subscription first
        cancel()
        if (activityTaskCounter.get() <= 0) {
            log.info {
                "[$name] No active tasks. Shutdown complete."
            }
            return Mono.empty()
        }
        // Wait for all active tasks to complete
        return Flux.interval(Duration.ofMillis(100))
            .takeUntil {
                if (activityTaskCounter.get() <= 0) {
                    log.info {
                        "[$name] No active tasks. Shutdown complete."
                    }
                    return@takeUntil true
                }
                log.info {
                    "[$name] Waiting for ${activityTaskCounter.get()} active tasks to complete."
                }
                return@takeUntil false
            }.then()
    }
}
