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

package me.ahoo.wow.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.LongTaskTimer
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

/**
 * Pins every meter the hot path records — name, type, the full tag set and how often it was recorded — for one
 * fixed scenario. A cache inside [WowMetrics] must not change any line (9.3.0 WP X4).
 */
class WowMetricsSnapshotTest {
    private val commandSend = MetricDescriptor(
        component = "command_bus",
        operation = "send",
        context = "sales",
        aggregate = "order",
        message = "create_order",
        source = "commandBus",
    )
    private val eventAppend = MetricDescriptor(
        component = "event_store",
        operation = "append",
        context = "sales",
        aggregate = "order",
        source = "eventStore",
    )
    private val dispatcherHandle = MetricDescriptor(
        component = "dispatcher",
        operation = "handle",
        context = "sales",
        aggregate = "order",
        processor = "OrderProjector",
        source = "OrderProjector",
    )
    private val eventProcess = MetricDescriptor(
        component = "domain_event_handler",
        operation = "process",
        context = "sales",
        aggregate = "order",
        message = "order_created",
        processor = "OrderProjector",
    )
    private val eventReceive = MetricDescriptor(
        component = "domain_event_bus",
        operation = "receive",
        context = "sales",
        aggregate = "order",
        source = "domainEventBus",
        subscriber = "fallback",
    )

    @Test
    fun `meter names tags and counts should match the snapshot`() {
        val registry = SimpleMeterRegistry()
        val metrics = WowMetrics(registry)

        record(metrics)

        registry.snapshot().assert().containsExactlyElementsOf(EXPECTED)
    }

    @Test
    fun `a second metrics instance on the same registry should record into the same meters`() {
        val registry = SimpleMeterRegistry()

        record(WowMetrics(registry))
        record(WowMetrics(registry))

        registry.snapshot().assert().containsExactlyElementsOf(
            EXPECTED.map { line ->
                val (prefix, count) = line.split(" #")
                "$prefix #${count.toLong() * 2}"
            },
        )
    }

    /**
     * One receiver serves a bounded context with two aggregates (9.3.0 X7): the series are those two per-aggregate
     * receivers recorded in 9.2, each tagged with its own context and aggregate, never `multiple`.
     */
    @Test
    fun `a multi-aggregate context receive should record the per-aggregate series`() {
        val registry = SimpleMeterRegistry()
        val metrics = WowMetrics(registry)
        val order = MaterializedNamedAggregate("sales", "order")
        val cart = MaterializedNamedAggregate("sales", "cart")
        val contextReceive = eventReceive.copy(
            context = MetricDescriptor.MULTIPLE,
            aggregate = MetricDescriptor.MULTIPLE
        )

        StepVerifier.create(
            metrics.stream(Flux.just(order, cart, order), contextReceive, linkedSetOf(order, cart)) { it }
                .writeMetricsSubscriber("projector"),
        ).expectNextCount(3)
            .verifyComplete()

        registry.snapshot().assert().containsExactlyElementsOf(
            listOf("cart" to 1, "order" to 2).flatMap { (aggregate, messages) ->
                val tags = "aggregate=$aggregate,component=domain_event_bus,context=sales,message=none,operation=receive"
                listOf(
                    "COUNTER wow.stream.messages {$tags,processor=none,source=domainEventBus," +
                        "subscriber=projector} #$messages",
                    "COUNTER wow.stream.terminations {aggregate=$aggregate,component=domain_event_bus,context=sales," +
                        "exception=none,message=none,operation=receive,outcome=success,processor=none," +
                        "source=domainEventBus,subscriber=projector} #1",
                    "LONG_TASK_TIMER wow.stream.active {$tags,processor=none,source=domainEventBus," +
                        "subscriber=projector} #0",
                )
            }.sorted(),
        )
    }

    private fun record(metrics: WowMetrics) {
        repeat(3) {
            StepVerifier.create(metrics.operation(Mono.empty<String>(), commandSend)).verifyComplete()
        }
        StepVerifier.create(metrics.operation(Mono.error<String>(IllegalStateException("failed")), commandSend))
            .expectError(IllegalStateException::class.java)
            .verify()
        StepVerifier.create(metrics.operation(Mono.never<String>(), commandSend))
            .thenCancel()
            .verify()
        repeat(2) {
            StepVerifier.create(metrics.operation(Flux.just("one", "two"), eventAppend))
                .expectNextCount(2)
                .verifyComplete()
        }
        StepVerifier.create(metrics.operation(Flux.error<String>(IllegalArgumentException("bad")), eventAppend))
            .expectError(IllegalArgumentException::class.java)
            .verify()
        repeat(4) {
            StepVerifier.create(metrics.operation(Mono.just("handled"), dispatcherHandle))
                .expectNext("handled")
                .verifyComplete()
        }
        repeat(5) {
            metrics.processingOutcome(eventProcess, "handled")
        }
        metrics.processingOutcome(eventProcess, "failure_recorded")
        StepVerifier.create(
            metrics.stream(Flux.just("one", "two", "three"), eventReceive)
                .writeMetricsSubscriber("projector"),
        ).expectNextCount(3)
            .verifyComplete()
        StepVerifier.create(metrics.stream(Flux.error<String>(IllegalStateException("closed")), eventReceive))
            .expectError(IllegalStateException::class.java)
            .verify()
    }

    private fun MeterRegistry.snapshot(): List<String> = meters.map { it.snapshotLine() }.sorted()

    private fun Meter.snapshotLine(): String {
        val tags = id.tags.joinToString(",") { "${it.key}=${it.value}" }
        val recorded = when (this) {
            is Timer -> count()
            is DistributionSummary -> count()
            is Counter -> count().toLong()
            is LongTaskTimer -> activeTasks().toLong()
            else -> error("Unexpected meter type: ${id.type}")
        }
        return "${id.type} ${id.name} {$tags} #$recorded"
    }

    private companion object {
        private const val EVENT_RECEIVE_TAGS =
            "aggregate=order,component=domain_event_bus,context=sales,message=none,operation=receive"

        val EXPECTED: List<String> = listOf(
            "COUNTER wow.processing.outcomes {aggregate=order,component=domain_event_handler,context=sales," +
                "message=order_created,operation=process,outcome=failure_recorded,processor=OrderProjector," +
                "source=none,subscriber=none} #1",
            "COUNTER wow.processing.outcomes {aggregate=order,component=domain_event_handler,context=sales," +
                "message=order_created,operation=process,outcome=handled,processor=OrderProjector," +
                "source=none,subscriber=none} #5",
            "COUNTER wow.stream.messages {$EVENT_RECEIVE_TAGS,processor=none,source=domainEventBus," +
                "subscriber=fallback} #0",
            "COUNTER wow.stream.messages {$EVENT_RECEIVE_TAGS,processor=none,source=domainEventBus," +
                "subscriber=projector} #3",
            "COUNTER wow.stream.terminations {aggregate=order,component=domain_event_bus,context=sales," +
                "exception=IllegalStateException,message=none,operation=receive,outcome=error,processor=none," +
                "source=domainEventBus,subscriber=fallback} #1",
            "COUNTER wow.stream.terminations {aggregate=order,component=domain_event_bus,context=sales," +
                "exception=none,message=none,operation=receive,outcome=success,processor=none," +
                "source=domainEventBus,subscriber=projector} #1",
            "DISTRIBUTION_SUMMARY wow.operation.items {aggregate=order,component=event_store,context=sales," +
                "exception=IllegalArgumentException,message=none,operation=append,outcome=error,processor=none," +
                "source=eventStore,subscriber=none} #1",
            "DISTRIBUTION_SUMMARY wow.operation.items {aggregate=order,component=event_store,context=sales," +
                "exception=none,message=none,operation=append,outcome=success,processor=none," +
                "source=eventStore,subscriber=none} #2",
            "LONG_TASK_TIMER wow.stream.active {$EVENT_RECEIVE_TAGS,processor=none,source=domainEventBus," +
                "subscriber=fallback} #0",
            "LONG_TASK_TIMER wow.stream.active {$EVENT_RECEIVE_TAGS,processor=none,source=domainEventBus," +
                "subscriber=projector} #0",
            "TIMER wow.operation {aggregate=order,component=command_bus,context=sales,exception=IllegalStateException," +
                "message=create_order,operation=send,outcome=error,processor=none,source=commandBus," +
                "subscriber=none} #1",
            "TIMER wow.operation {aggregate=order,component=command_bus,context=sales,exception=none," +
                "message=create_order,operation=send,outcome=cancelled,processor=none,source=commandBus," +
                "subscriber=none} #1",
            "TIMER wow.operation {aggregate=order,component=command_bus,context=sales,exception=none," +
                "message=create_order,operation=send,outcome=success,processor=none,source=commandBus," +
                "subscriber=none} #3",
            "TIMER wow.operation {aggregate=order,component=dispatcher,context=sales,exception=none," +
                "message=none,operation=handle,outcome=success,processor=OrderProjector,source=OrderProjector," +
                "subscriber=none} #4",
            "TIMER wow.operation {aggregate=order,component=event_store,context=sales," +
                "exception=IllegalArgumentException,message=none,operation=append,outcome=error,processor=none," +
                "source=eventStore,subscriber=none} #1",
            "TIMER wow.operation {aggregate=order,component=event_store,context=sales,exception=none," +
                "message=none,operation=append,outcome=success,processor=none,source=eventStore," +
                "subscriber=none} #2",
        )
    }
}
