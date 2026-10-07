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

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MeterCacheTest {
    private val descriptor = MetricDescriptor(
        component = "command_bus",
        operation = "send",
        context = "sales",
        aggregate = "order",
        source = "commandBus",
    )

    @Test
    fun `equal descriptors should share one set of meters`() {
        val cache = MeterCache(SimpleMeterRegistry())

        val meters = cache.of(descriptor)

        cache.of(descriptor.copy()).assert().isSameAs(meters)
        cache.size.assert().isEqualTo(1)
        val terminal = meters.terminal(MetricOutcome.SUCCESS, MetricDescriptor.NONE)
        meters.terminal(MetricOutcome.SUCCESS, MetricDescriptor.NONE).assert().isSameAs(terminal)
        meters.terminal(MetricOutcome.ERROR, MetricDescriptor.NONE).assert().isNotSameAs(terminal)
        terminal.operation().assert().isSameAs(terminal.operation())
        terminal.operationItems().assert().isSameAs(terminal.operationItems())
        terminal.streamTerminations().assert().isSameAs(terminal.streamTerminations())
        meters.processingOutcome("handled").assert().isSameAs(meters.processingOutcome("handled"))
        meters.streamActive().assert().isSameAs(meters.streamActive())
        meters.streamMessages().assert().isSameAs(meters.streamMessages())
    }

    @Test
    fun `cached meters should be the registry meters for the same name and tags`() {
        val registry = SimpleMeterRegistry()
        val meters = MeterCache(registry).of(descriptor)
        val terminalTags = descriptor.terminalTags(MetricOutcome.ERROR, "IllegalStateException")
        val terminal = meters.terminal(MetricOutcome.ERROR, "IllegalStateException")

        terminal.operation().assert().isSameAs(registry.timer(WowMetricNames.OPERATION, terminalTags))
        terminal.operationItems().assert()
            .isSameAs(registry.summary(WowMetricNames.OPERATION_ITEMS, terminalTags))
        terminal.streamTerminations().assert()
            .isSameAs(registry.counter(WowMetricNames.STREAM_TERMINATIONS, terminalTags))
        meters.processingOutcome("handled").assert().isSameAs(
            registry.counter(
                WowMetricNames.PROCESSING_OUTCOMES,
                descriptor.baseTags().and(MetricDescriptor.OUTCOME_TAG, "handled"),
            ),
        )
        meters.streamActive().assert()
            .isSameAs(registry.more().longTaskTimer(WowMetricNames.STREAM_ACTIVE, descriptor.baseTags()))
        meters.streamMessages().assert()
            .isSameAs(registry.counter(WowMetricNames.STREAM_MESSAGES, descriptor.baseTags()))
    }

    @Test
    fun `removing a meter from the registry should drop the cache`() {
        val registry = SimpleMeterRegistry()
        val cache = MeterCache(registry)
        val removed = cache.of(descriptor).terminal(MetricOutcome.SUCCESS, MetricDescriptor.NONE).operation()

        registry.remove(removed)

        cache.size.assert().isZero()
        val registered = cache.of(descriptor).terminal(MetricOutcome.SUCCESS, MetricDescriptor.NONE).operation()
        registered.assert().isNotSameAs(removed)
        registry.meters.assert().containsExactly(registered)
    }

    @Test
    fun `recording after the registry was cleared should register the meters again`() {
        val registry = SimpleMeterRegistry()
        val metrics = WowMetrics(registry)
        metrics.operation(Mono.just("first"), descriptor).block()
        metrics.processingOutcome(descriptor, "handled")

        registry.clear()
        metrics.operation(Mono.just("second"), descriptor).block()
        metrics.processingOutcome(descriptor, "handled")

        registry.get(WowMetricNames.OPERATION).timer().count().assert().isEqualTo(1)
        registry.get(WowMetricNames.PROCESSING_OUTCOMES).counter().count().assert().isEqualTo(1.0)
    }

    @Test
    fun `a meter that failed to register should be tried again`() {
        var failing = true
        val registry = SimpleMeterRegistry().apply {
            config().meterFilter(
                object : MeterFilter {
                    override fun map(id: Meter.Id): Meter.Id {
                        check(!(failing && id.name == WowMetricNames.OPERATION_ITEMS)) { "unavailable" }
                        return id
                    }
                },
            )
        }
        val metrics = WowMetrics(registry)

        metrics.operation(Flux.just("one"), descriptor).blockLast()
        registry.find(WowMetricNames.OPERATION_ITEMS).summary().assert().isNull()

        failing = false
        metrics.operation(Flux.just("one", "two"), descriptor).blockLast()

        registry.get(WowMetricNames.OPERATION).timer().count().assert().isEqualTo(2)
        registry.get(WowMetricNames.OPERATION_ITEMS).summary().totalAmount().assert().isEqualTo(2.0)
    }

    @Test
    fun `a failed terminal lookup should not affect the publisher`() {
        val registry = SimpleMeterRegistry()
        val conflicting = descriptor.terminalTags(MetricOutcome.SUCCESS, MetricDescriptor.NONE)
        registry.counter(WowMetricNames.OPERATION, conflicting)
        val cache = MeterCache(registry)

        assertThrows<IllegalArgumentException> {
            cache.of(descriptor).terminal(MetricOutcome.SUCCESS, MetricDescriptor.NONE).operation()
        }
        WowMetrics(registry).operation(Mono.just("value"), descriptor).block().assert().isEqualTo("value")
    }

    @Test
    fun `removing a meter the cache did not hand out should keep the cache`() {
        val registry = SimpleMeterRegistry()
        val cache = MeterCache(registry)
        val meters = cache.of(descriptor)
        val operation = meters.terminal(MetricOutcome.SUCCESS, MetricDescriptor.NONE).operation()

        registry.remove(registry.counter("app.requests"))

        cache.size.assert().isEqualTo(1)
        cache.of(descriptor).assert().isSameAs(meters)
        meters.terminal(MetricOutcome.SUCCESS, MetricDescriptor.NONE).operation().assert().isSameAs(operation)
    }

    @Test
    fun `filters configured before first use should apply to cached meters`() {
        val registry = SimpleMeterRegistry().apply {
            config()
                .meterFilter(MeterFilter.commonTags(listOf(Tag.of("region", "east"))))
                .meterFilter(
                    object : MeterFilter {
                        override fun map(id: Meter.Id): Meter.Id =
                            id.withName(id.name.replaceFirst("wow.", "app.wow."))
                    },
                )
        }
        val cache = MeterCache(registry)
        val metrics = WowMetrics(registry)

        metrics.operation(Mono.just("first"), descriptor).block()
        metrics.operation(Mono.just("second"), descriptor).block()

        registry.find(WowMetricNames.OPERATION).timer().assert().isNull()
        val renamed = registry.get("app.wow.operation").tag("region", "east").timer()
        renamed.count().assert().isEqualTo(2)
        cache.of(descriptor).terminal(MetricOutcome.SUCCESS, MetricDescriptor.NONE).operation()
            .assert().isSameAs(renamed)

        // The cache recognises a removed meter by its filtered id, so a renamed meter still drops it.
        registry.remove(renamed)
        cache.size.assert().isZero()
        metrics.operation(Mono.just("third"), descriptor).block()
        registry.get("app.wow.operation").timer().count().assert().isEqualTo(1)
    }

    @Test
    fun `a meter removed before the cache remembers it should not stay cached`() {
        // The removal runs after the registry handed the meter out and before the cache has remembered it, so the
        // removal listener finds nothing to drop: the cache must notice by itself.
        val registry = SimpleMeterRegistry()
        val cache = MeterCache(registry)
        cache.of(descriptor)
        val removed = cache.own { registry.counter("removed.meter").also(registry::remove) }

        cache.size.assert().isZero()
        registry.find("removed.meter").counter().assert().isNull()
        cache.own { registry.counter("removed.meter") }.assert().isNotSameAs(removed)
        registry.get("removed.meter").counter().assert().isNotNull()
    }

    /**
     * Recordings race removals of the meters they record into. Once the removals stop, every recording lands in a
     * registered meter: none is lost into a removed one that the cache still holds.
     */
    @Test
    fun `concurrent recordings racing the removal listener end on registered meters`() {
        val registry = SimpleMeterRegistry()
        val metrics = WowMetrics(registry)
        val recorders = Executors.newFixedThreadPool(4)
        val stop = AtomicBoolean()
        try {
            val recording = (0 until 4).map { worker ->
                recorders.submit {
                    val workerDescriptor = descriptor.copy(processor = "processor-${worker % 2}")
                    while (!stop.get()) {
                        metrics.operation(Mono.just(worker), workerDescriptor).block()
                        metrics.processingOutcome(workerDescriptor, "handled")
                    }
                }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
            while (System.nanoTime() < deadline) {
                registry.meters.forEach(registry::remove)
            }
            stop.set(true)
            recording.forEach { it.get(5, TimeUnit.SECONDS) }

            registry.clear()
            repeat(3) {
                listOf("processor-0", "processor-1").forEach { processor ->
                    val processorDescriptor = descriptor.copy(processor = processor)
                    metrics.operation(Mono.just(it), processorDescriptor).block()
                    metrics.processingOutcome(processorDescriptor, "handled")
                }
            }

            listOf("processor-0", "processor-1").forEach { processor ->
                registry.get(WowMetricNames.OPERATION).tag(MetricDescriptor.PROCESSOR_TAG, processor).timer()
                    .count().assert().isEqualTo(3)
                registry.get(WowMetricNames.PROCESSING_OUTCOMES).tag(MetricDescriptor.PROCESSOR_TAG, processor)
                    .counter().count().assert().isEqualTo(3.0)
            }
        } finally {
            stop.set(true)
            recorders.shutdownNow()
        }
    }

    @Test
    fun `a meter removed while a stream runs should be registered again when it terminates`() {
        val registry = SimpleMeterRegistry()
        val metrics = WowMetrics(registry)
        metrics.stream(Flux.just("first"), descriptor).blockLast()
        val terminations = registry.get(WowMetricNames.STREAM_TERMINATIONS).counter()
        terminations.count().assert().isEqualTo(1.0)

        val source = Sinks.many().unicast().onBackpressureBuffer<String>()
        val running = metrics.stream(source.asFlux(), descriptor).collectList().toFuture()
        registry.remove(terminations)
        source.tryEmitComplete()
        running.get()

        val registered = registry.get(WowMetricNames.STREAM_TERMINATIONS).counter()
        registered.assert().isNotSameAs(terminations)
        registered.count().assert().isEqualTo(1.0)
    }
}
