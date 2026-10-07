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

package me.ahoo.wow.runtime

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.messaging.transport.TopicNaming
import me.ahoo.wow.messaging.transport.TransportCommandBus
import me.ahoo.wow.messaging.transport.TransportServerCommandExchange
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * B8: a graceful stop under sustained ingress from a durable transport. Records keep arriving faster than the quiet
 * period, so the runtime only becomes idle once it stops pulling them.
 */
class SustainedIngressShutdownTest {
    companion object {
        private val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val QUIET_PERIOD: Duration = Duration.ofMillis(200)
        private const val AGGREGATES = 8
    }

    @Test
    fun `graceful stop reaches quiescence under sustained durable ingress without committing unprocessed records`() {
        val scenario = Scenario()
        val runtime = WowRuntime(
            components = listOf(scenario.durableDispatcher, scenario.derivedDispatcher),
            shutdownTimeout = SHUTDOWN_TIMEOUT,
            shutdownQuietPeriod = QUIET_PERIOD,
        )
        runtime.start().block(Duration.ofSeconds(5))
        val sequence = AtomicInteger()
        // About one record per millisecond: far more often than the quiet period.
        val producer = Flux.interval(Duration.ofMillis(1), Schedulers.single())
            .onBackpressureDrop()
            .concatMap { scenario.durableBus.send(durableCommand(sequence.getAndIncrement())) }
            .subscribe()
        try {
            awaitUntil { scenario.processed.size >= 200 }

            val stopStarted = System.nanoTime()
            val stopFailure = runCatching { runtime.stopGracefully().block(SHUTDOWN_TIMEOUT.multipliedBy(2)) }
                .exceptionOrNull()
            val stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStarted)
            println("Graceful stop under sustained ingress took $stopMillis ms (deadline $SHUTDOWN_TIMEOUT).")

            stopFailure.assert().isNull()
            stopMillis.assert().isLessThan(SHUTDOWN_TIMEOUT.toMillis() / 2)
            // Ingress went on during and after the stop.
            awaitUntil { scenario.broker.size > scenario.broker.delivered + 50 }
        } finally {
            producer.dispose()
            runtime.forceStop()
        }

        scenario.assertNothingCommittedUnprocessed()
    }

    /**
     * A dispatcher on a durable transport whose handler, for every record, also sends a command in-process to a second
     * dispatcher: the chain derived from a record must complete in the drain.
     */
    private inner class Scenario {
        val broker = DurableLogTransport()
        val durableBus = TransportCommandBus(broker, TopicNaming { it.commandTopic() })
        private val derivedBus = InMemoryCommandBus()
        val processed = ConcurrentHashMap<Int, AtomicInteger>()
        private val processedOrder = ConcurrentHashMap<String, MutableList<Int>>()
        private val derivedSent = AtomicInteger()
        private val derivedProcessed = AtomicInteger()

        val durableDispatcher = CommandDispatcher(
            name = "durable-dispatcher",
            namedAggregates = setOf(MOCK_AGGREGATE_METADATA),
            commandBus = durableBus,
            commandHandler = handler { exchange ->
                val offset = (exchange as TransportServerCommandExchange<*>).record.id.toInt()
                Mono.delay(Duration.ofMillis(1))
                    .then(
                        Mono.defer {
                            processed.computeIfAbsent(offset) { AtomicInteger() }.incrementAndGet()
                            processedOrder.computeIfAbsent(exchange.message.aggregateId.id) {
                                CopyOnWriteArrayList()
                            } += offset
                            derivedSent.incrementAndGet()
                            derivedBus.send(derivedCommand(offset))
                        },
                    )
                    .then(exchange.acknowledge())
            },
        )

        val derivedDispatcher = CommandDispatcher(
            name = "derived-dispatcher",
            namedAggregates = setOf(MOCK_AGGREGATE_METADATA),
            commandBus = derivedBus,
            commandHandler = handler {
                Mono.delay(Duration.ofMillis(1)).then(Mono.fromRunnable { derivedProcessed.incrementAndGet() })
            },
        )

        fun assertNothingCommittedUnprocessed() {
            // No record was processed twice.
            processed.values.forEach { it.get().assert().isEqualTo(1) }
            // Every acknowledged record was processed, and the committed prefix holds only processed records.
            broker.acknowledged.forEach { processed.keys.assert().contains(it) }
            val committed = broker.committedOffset()
            (0 until committed).forEach { processed.keys.assert().contains(it) }
            // Records the runtime never pulled stay uncommitted for another member.
            committed.assert().isLessThan(broker.size)
            // Per-aggregate order held.
            processedOrder.values.forEach { offsets -> offsets.assert().isSorted() }
            // In-process work derived during the drain completed.
            derivedProcessed.get().assert().isEqualTo(derivedSent.get())
        }
    }

    private fun handler(handle: (ServerCommandExchange<*>) -> Mono<Void>): CommandHandler =
        object : CommandHandler {
            override fun handle(
                exchange: ServerCommandExchange<*>,
                aggregateMetadata: AggregateMetadata<*, *>,
            ): Mono<Void> = Mono.defer { handle(exchange) }
        }

    private fun durableCommand(sequence: Int) =
        "aggregate-${sequence % AGGREGATES}".let { aggregateId ->
            MockCreateAggregate(id = aggregateId, data = "$sequence").toCommandMessage(aggregateId = aggregateId)
        }

    private fun derivedCommand(offset: Int) =
        "derived-${offset % AGGREGATES}".let { aggregateId ->
            MockCreateAggregate(id = aggregateId, data = "$offset").toCommandMessage(aggregateId = aggregateId)
        }

    private fun NamedAggregate.commandTopic(): String = "$contextName.$aggregateName.command"

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not reached within 10 s." }
            Thread.sleep(5)
        }
    }
}
