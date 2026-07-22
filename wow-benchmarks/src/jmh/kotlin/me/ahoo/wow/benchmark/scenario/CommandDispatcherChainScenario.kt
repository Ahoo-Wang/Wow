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

package me.ahoo.wow.benchmark.scenario

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkIds
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.modeling.command.dispatcher.AggregateCommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import java.util.concurrent.atomic.AtomicInteger

/**
 * Attribute key under which the per-exchange completion [Sinks.Empty] is stored on the
 * [ServerCommandExchange]. The dispatch-chain handler emits it when the chain finishes.
 */
const val DISPATCH_CHAIN_COMPLETION_KEY: String = "__DISPATCH_CHAIN_COMPLETION__"

/**
 * Simulated per-command work cost injected into the noop handler.
 *
 * - [NOOP]: handler returns immediately. The benchmark measures the pure dispatch-chain
 *   overhead (groupBy + publishOn + concatMap + attribute write).
 * - [SIMULATED]: handler consumes a small fixed CPU budget, exposing how much of the
 *   end-to-end latency is dispatch overhead versus handler work.
 */
enum class HandlerCost {
    NOOP,
    SIMULATED,
}

/**
 * Isolated scenario that measures the [AggregateCommandDispatcher] dispatch chain
 * (`groupBy` → `publishOn` → `concatMap`) in isolation, stripped of the command gateway,
 * command bus, aggregate processor, and event-store paths.
 *
 * A controllable [Sinks.Many] feeds pre-built [ServerCommandExchange] instances straight
 * into the dispatcher's `messageFlux`. A completion [Sinks.Empty] per command lets the
 * benchmark block until the dispatch chain has fully processed that exchange, so each
 * measured operation covers the ingress-to-handled round trip through the scheduler.
 *
 * The scenario can cycle through multiple aggregate IDs, but concurrency claims require
 * the caller to keep multiple messages outstanding. A caller that emits and immediately
 * awaits one exchange only measures sequential ingress-to-handler completion.
 *
 * @author ahoo wang
 */
class CommandDispatcherChainScenario private constructor(
    val dispatcher: AggregateCommandDispatcher<*, *>,
    val messageSink: Sinks.Many<ServerCommandExchange<*>>,
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val aggregateIdCardinality: Int,
    private val handlerCost: HandlerCost,
    private val schedulerSupplier: AggregateSchedulerSupplier,
    private val commandMessages: List<CommandMessage<*>>,
) : AutoCloseable {

    private val aggregateIdCycle = AtomicInteger(0)

    /**
     * Builds the next [ServerCommandExchange] together with its completion sink.
     *
     * Only the lightweight per-invocation objects (exchange + completion sink + attribute
     * write) are allocated here; the command messages are pre-built once in [create] so the
     * benchmark's measured time reflects the dispatch chain, not message construction.
     */
    fun nextExchange(): DispatchedExchange {
        val commandMessage = commandMessages[aggregateIdCycle.getAndIncrement() % commandMessages.size]
        val completionSink = Sinks.empty<Void>()
        val exchange = SimpleServerCommandExchange(commandMessage)
        exchange.setAggregateMetadata(aggregateMetadata)
        exchange.setAttribute(DISPATCH_CHAIN_COMPLETION_KEY, completionSink)
        return DispatchedExchange(exchange, completionSink)
    }

    override fun close() {
        dispatcher.stopGracefully().block()
        schedulerSupplier.stopGracefully().block()
    }

    companion object {
        fun create(
            aggregateMetadata: AggregateMetadata<*, *> = BenchmarkAggregates.cartMetadata,
            aggregateIdCardinality: Int = 1,
            handlerCost: HandlerCost = HandlerCost.NOOP,
            schedulerStrategy: SchedulerStrategy = SchedulerStrategy.PARALLEL,
            parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
        ): CommandDispatcherChainScenario {
            val messageSink = Sinks.many().unicast().onBackpressureBuffer<ServerCommandExchange<*>>()
            val handler = DispatchChainHandler(handlerCost)
            val schedulerSupplier = schedulerStrategy.toSchedulerSupplier()
            // Pre-build the command messages once (the expensive part: metadata reflection,
            // aggregate-id construction) so per-invocation timing covers only the dispatch
            // chain and the lightweight exchange/sink allocation, not message construction.
            val cardinality = aggregateIdCardinality.coerceAtLeast(1)
            val commandMessages = List(cardinality) { index ->
                val aggregateId = if (cardinality == 1) {
                    BenchmarkAggregates.FIXED_AGGREGATE_ID
                } else {
                    "${BenchmarkAggregates.FIXED_AGGREGATE_ID}-$index"
                }
                val commandId = BenchmarkIds.nextGlobalId()
                AddCartItem(productId = "productId").toCommandMessage(
                    id = commandId,
                    requestId = commandId,
                    aggregateId = aggregateId,
                    namedAggregate = BenchmarkAggregates.namedAggregate,
                )
            }
            @Suppress("UNCHECKED_CAST")
            val dispatcher = AggregateCommandDispatcher<Any, Any>(
                aggregateMetadata = aggregateMetadata as AggregateMetadata<Any, Any>,
                messageFlux = messageSink.asFlux(),
                parallelism = parallelism,
                commandHandler = handler,
                scheduler = schedulerSupplier.getOrInitialize(BenchmarkAggregates.namedAggregate),
            )
            dispatcher.start()
            return CommandDispatcherChainScenario(
                dispatcher = dispatcher,
                messageSink = messageSink,
                aggregateMetadata = aggregateMetadata,
                aggregateIdCardinality = cardinality,
                handlerCost = handlerCost,
                schedulerSupplier = schedulerSupplier,
                commandMessages = commandMessages,
            )
        }
    }
}

/**
 * Pairs a [ServerCommandExchange] with the completion sink the dispatch-chain handler
 * emits once the chain finishes processing it.
 */
class DispatchedExchange(
    val exchange: ServerCommandExchange<*>,
    private val completionSink: Sinks.Empty<Void>,
) {
    /**
     * Completes when the dispatch chain has finished handling [exchange].
     * The benchmark blocks on this to measure the full ingress-to-handled round trip.
     */
    fun await(): Mono<Void> = completionSink.asMono()
}

/**
 * Noop-ish command handler that completes the per-exchange completion sink (stored under
 * [DISPATCH_CHAIN_COMPLETION_KEY]) when the dispatch chain finishes invoking it. Optional
 * simulated CPU work is bounded so the handler remains non-blocking and deterministic.
 */
private class DispatchChainHandler(
    private val handlerCost: HandlerCost,
) : CommandHandler {

    @Suppress("UNCHECKED_CAST")
    override fun handle(context: ServerCommandExchange<*>): Mono<Void> {
        if (handlerCost == HandlerCost.SIMULATED) {
            Blackhole.consumeCPU(SIMULATED_CPU_TOKENS)
        }
        val completionSink = checkNotNull(
            context.getAttribute(DISPATCH_CHAIN_COMPLETION_KEY) as? Sinks.Empty<Void>
        ) {
            "Dispatch-chain completion sink is missing."
        }
        // Reactor invokes nested doFinally callbacks from the outside in. The dispatcher's
        // outer doFinally therefore decrements its active-task counter before this callback
        // acknowledges the exchange to the benchmark.
        val done: Mono<Void> = Mono.empty()
        return done.doFinally { completionSink.tryEmitEmpty().orThrow() }
    }

    private companion object {
        /**
         * Small, fixed CPU budget so the simulated handler stays well below the dispatch
         * overhead being measured while still being non-trivial.
         */
        private const val SIMULATED_CPU_TOKENS = 16L
    }
}
