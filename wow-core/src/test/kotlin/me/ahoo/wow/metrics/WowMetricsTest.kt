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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class WowMetricsTest {
    private val descriptor = MetricDescriptor(
        component = "event_store",
        operation = "load",
        context = "sales",
        aggregate = "Order",
        source = "mongo",
    )

    @Test
    fun `operation should record success duration and item count`() {
        val registry = SimpleMeterRegistry()
        val metrics = WowMetrics(registry)

        StepVerifier.create(metrics.operation(Flux.just("one", "two"), descriptor))
            .expectNext("one", "two")
            .verifyComplete()

        val operationTimer = registry.find(WowMetricNames.OPERATION)
            .tags(descriptor.terminalTags(MetricOutcome.SUCCESS, MetricDescriptor.NONE))
            .timer()
        operationTimer.assert().isNotNull()
        requireNotNull(operationTimer).count().assert().isEqualTo(1)
        val itemSummary = registry.find(WowMetricNames.OPERATION_ITEMS)
            .tags(descriptor.terminalTags(MetricOutcome.SUCCESS, MetricDescriptor.NONE))
            .summary()
        itemSummary.assert().isNotNull()
        requireNotNull(itemSummary).totalAmount().assert().isEqualTo(2.0)
    }

    @Test
    fun `operation should distinguish error and cancellation`() {
        val registry = SimpleMeterRegistry()
        val metrics = WowMetrics(registry)

        StepVerifier.create(metrics.operation(Mono.error<String>(IllegalStateException("failed")), descriptor))
            .expectError(IllegalStateException::class.java)
            .verify()
        StepVerifier.create(metrics.operation(Flux.never<String>(), descriptor))
            .thenCancel()
            .verify()

        val errorTimer = registry.find(WowMetricNames.OPERATION)
            .tags(descriptor.terminalTags(MetricOutcome.ERROR, IllegalStateException::class.simpleName!!))
            .timer()
        errorTimer.assert().isNotNull()
        requireNotNull(errorTimer).count().assert().isEqualTo(1)
        val cancellationTimer = registry.find(WowMetricNames.OPERATION)
            .tags(descriptor.terminalTags(MetricOutcome.CANCELLED, MetricDescriptor.NONE))
            .timer()
        cancellationTimer.assert().isNotNull()
        requireNotNull(cancellationTimer).count().assert().isEqualTo(1)
    }

    @Test
    fun `anonymous exceptions should use the bounded fallback tag`() {
        val registry = SimpleMeterRegistry()
        val metrics = WowMetrics(registry)
        val anonymousError = object : RuntimeException("failed") {}

        StepVerifier.create(metrics.operation(Mono.error<String>(anonymousError), descriptor))
            .expectErrorMatches { it === anonymousError }
            .verify()

        registry.find(WowMetricNames.OPERATION)
            .tags(descriptor.terminalTags(MetricOutcome.ERROR, MetricDescriptor.NONE))
            .timer()
            .assert()
            .isNotNull()
    }

    @Test
    fun `one meter failure should not affect the publisher or sibling meters`() {
        val registry = SimpleMeterRegistry()
        val metrics = WowMetrics(registry)
        val terminalTags = descriptor.terminalTags(MetricOutcome.SUCCESS, MetricDescriptor.NONE)
        registry.counter(WowMetricNames.OPERATION, terminalTags)

        StepVerifier.create(metrics.operation(Flux.just("value"), descriptor))
            .expectNext("value")
            .verifyComplete()

        registry.find(WowMetricNames.OPERATION_ITEMS)
            .tags(terminalTags)
            .summary()
            .assert()
            .isNotNull()
    }

    @Test
    fun `stream should record messages and termination with subscriber from context`() {
        val registry = SimpleMeterRegistry()
        val metrics = WowMetrics(registry)
        val streamDescriptor = descriptor.copy(
            component = "domain_event_bus",
            operation = "receive",
            subscriber = "fallback",
        )

        StepVerifier.create(
            metrics.stream(Flux.just("one", "two"), streamDescriptor)
                .writeMetricsSubscriber("projection-worker"),
        ).expectNext("one", "two")
            .verifyComplete()

        val resolvedDescriptor = streamDescriptor.copy(subscriber = "projection-worker")
        val messageCounter = registry.find(WowMetricNames.STREAM_MESSAGES)
            .tags(resolvedDescriptor.baseTags())
            .counter()
        messageCounter.assert().isNotNull()
        requireNotNull(messageCounter).count().assert().isEqualTo(2.0)
        val terminationCounter = registry.find(WowMetricNames.STREAM_TERMINATIONS)
            .tags(resolvedDescriptor.terminalTags(MetricOutcome.SUCCESS, MetricDescriptor.NONE))
            .counter()
        terminationCounter.assert().isNotNull()
        requireNotNull(terminationCounter).count().assert().isEqualTo(1.0)
        val activeTimer = registry.find(WowMetricNames.STREAM_ACTIVE)
            .tags(resolvedDescriptor.baseTags())
            .longTaskTimer()
        activeTimer.assert().isNotNull()
        requireNotNull(activeTimer).activeTasks().assert().isEqualTo(0)
    }

    @Test
    fun `stream should distinguish error and cancellation`() {
        val registry = SimpleMeterRegistry()
        val metrics = WowMetrics(registry)

        StepVerifier.create(metrics.stream(Flux.error<String>(IllegalArgumentException("failed")), descriptor))
            .expectError(IllegalArgumentException::class.java)
            .verify()
        StepVerifier.create(metrics.stream(Flux.never<String>(), descriptor))
            .thenCancel()
            .verify()

        registry.find(WowMetricNames.STREAM_TERMINATIONS)
            .tags(descriptor.terminalTags(MetricOutcome.ERROR, IllegalArgumentException::class.simpleName!!))
            .counter()
            .assert()
            .isNotNull()
        registry.find(WowMetricNames.STREAM_TERMINATIONS)
            .tags(descriptor.terminalTags(MetricOutcome.CANCELLED, MetricDescriptor.NONE))
            .counter()
            .assert()
            .isNotNull()
    }

    @Test
    fun `stream meter failures should not affect the publisher`() {
        val registry = SimpleMeterRegistry()
        val metrics = WowMetrics(registry)
        registry.counter(WowMetricNames.STREAM_ACTIVE, descriptor.baseTags())
        registry.timer(WowMetricNames.STREAM_MESSAGES, descriptor.baseTags())
        registry.timer(
            WowMetricNames.STREAM_TERMINATIONS,
            descriptor.terminalTags(MetricOutcome.SUCCESS, MetricDescriptor.NONE),
        )

        StepVerifier.create(metrics.stream(Flux.just("one", "two"), descriptor))
            .expectNext("one", "two")
            .verifyComplete()
    }

    @Test
    fun `metrics subscriber should reject blank values`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            Flux.empty<String>().writeMetricsSubscriber(" ")
        }
    }

    @Test
    fun `registries should remain isolated`() {
        val firstRegistry = SimpleMeterRegistry()
        val secondRegistry = SimpleMeterRegistry()

        WowMetrics(firstRegistry).operation(Mono.just("first"), descriptor).block()
        WowMetrics(secondRegistry).operation(Mono.just("second"), descriptor).block()

        firstRegistry.find(WowMetricNames.OPERATION).timers().sumOf { it.count() }
            .assert().isEqualTo(1)
        secondRegistry.find(WowMetricNames.OPERATION).timers().sumOf { it.count() }
            .assert().isEqualTo(1)
    }

    @Test
    fun `disabled metrics should return original publisher`() {
        val mono = Mono.just("value")
        val flux = Flux.just("value")

        WowMetrics.NONE.operation(mono, descriptor).assert().isSameAs(mono)
        WowMetrics.NONE.operation(flux, descriptor).assert().isSameAs(flux)
        WowMetrics.NONE.stream(flux, descriptor).assert().isSameAs(flux)
    }
}
