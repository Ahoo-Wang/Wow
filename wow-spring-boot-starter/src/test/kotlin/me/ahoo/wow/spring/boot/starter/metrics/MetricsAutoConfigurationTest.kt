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

package me.ahoo.wow.spring.boot.starter.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.metrics.MetricDescriptor
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

class MetricsAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `post processor should decorate storage bindings once with their explicit names`() {
        val postProcessor = MetricsBeanPostProcessor(WowMetrics(SimpleMeterRegistry()))
        val eventBinding = EventStoreBinding(
            name = "custom-event-store",
            storage = null,
            eventStore = mockk<EventStore>(),
        )
        val snapshotBinding = SnapshotStoreBinding(
            name = "custom-snapshot-store",
            storage = null,
            snapshotStore = InMemorySnapshotStore(),
        )

        val meteredEventBinding = postProcessor.postProcessAfterInitialization(
            eventBinding,
            "customEventStoreBinding",
        ) as EventStoreBinding
        val meteredSnapshotBinding = postProcessor.postProcessAfterInitialization(
            snapshotBinding,
            "customSnapshotStoreBinding",
        ) as SnapshotStoreBinding

        meteredEventBinding.eventStore.assert().isInstanceOf(Decorator::class.java)
        meteredSnapshotBinding.snapshotStore.assert().isInstanceOf(Decorator::class.java)
        postProcessor.postProcessAfterInitialization(meteredEventBinding, "customEventStoreBinding")
            .assert()
            .isSameAs(meteredEventBinding)
        postProcessor.postProcessAfterInitialization(meteredSnapshotBinding, "customSnapshotStoreBinding")
            .assert()
            .isSameAs(meteredSnapshotBinding)
    }

    @Test
    fun `context should bind Wow metrics to its own meter registry`() {
        val meterRegistry = SimpleMeterRegistry()

        contextRunner
            .enableWow()
            .withBean(SimpleMeterRegistry::class.java, { meterRegistry })
            .withUserConfiguration(MetricsAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(WowMetrics::class.java)
                    .hasSingleBean(MetricsBeanPostProcessor::class.java)
                val metrics = context.getBean(WowMetrics::class.java)
                metrics.enabled.assert().isTrue()
                metrics.operation(
                    Mono.just("value"),
                    MetricDescriptor(component = "test", operation = "bind"),
                ).block()
                meterRegistry.find("wow.operation")
                    .tag("component", "test")
                    .tag("operation", "bind")
                    .timer()
                    .assert()
                    .isNotNull()
            }
    }

    @Test
    fun `enabled metrics without a registry should remain a local no-op`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(MetricsAutoConfiguration::class.java)
            .run { context ->
                context.getBean(WowMetrics::class.java).assert().isSameAs(WowMetrics.NONE)
                context.assert().hasSingleBean(MetricsBeanPostProcessor::class.java)
            }
    }

    @Test
    fun `disabled metrics should expose NONE and omit post processor`() {
        contextRunner
            .enableWow()
            .withBean(SimpleMeterRegistry::class.java, ::SimpleMeterRegistry)
            .withPropertyValues("${ConditionalOnMetricsEnabled.ENABLED_KEY}=false")
            .withUserConfiguration(MetricsAutoConfiguration::class.java)
            .run { context ->
                context.getBean(WowMetrics::class.java).assert().isSameAs(WowMetrics.NONE)
                context.assert().doesNotHaveBean(MetricsBeanPostProcessor::class.java)
            }
    }

    @Test
    fun `application contexts should isolate metrics enablement`() {
        metricsContextRunner(enabled = false).run { disabledContext ->
            metricsContextRunner(enabled = true).run { enabledContext ->
                disabledContext.getBean(WowMetrics::class.java).enabled.assert().isFalse()
                enabledContext.getBean(WowMetrics::class.java).enabled.assert().isTrue()
            }
        }
    }

    @Test
    fun `post processor should preserve inferred event store destruction`() {
        val closeCount = AtomicInteger()

        contextRunner
            .enableWow()
            .withBean(SimpleMeterRegistry::class.java, ::SimpleMeterRegistry)
            .withBean(
                "closeableEventStore",
                EventStore::class.java,
                { CloseableEventStore(closeCount) },
            )
            .withUserConfiguration(MetricsAutoConfiguration::class.java)
            .run { context ->
                context.getBean("closeableEventStore").assert().isInstanceOf(Decorator::class.java)
                closeCount.get().assert().isEqualTo(0)
            }

        closeCount.get().assert().isEqualTo(1)
    }

    @Test
    fun `auto configuration should back off for custom metrics beans`() {
        val customMetrics = WowMetrics(SimpleMeterRegistry())
        val customPostProcessor = MetricsBeanPostProcessor(customMetrics)

        contextRunner
            .enableWow()
            .withBean(WowMetrics::class.java, { customMetrics })
            .withBean(MetricsBeanPostProcessor::class.java, { customPostProcessor })
            .withUserConfiguration(MetricsAutoConfiguration::class.java)
            .run { context ->
                context.getBean(WowMetrics::class.java).assert().isSameAs(customMetrics)
                context.getBean(MetricsBeanPostProcessor::class.java).assert().isSameAs(customPostProcessor)
            }
    }

    private fun metricsContextRunner(enabled: Boolean): ApplicationContextRunner =
        contextRunner
            .enableWow()
            .withBean(SimpleMeterRegistry::class.java, ::SimpleMeterRegistry)
            .withPropertyValues("${ConditionalOnMetricsEnabled.ENABLED_KEY}=$enabled")
            .withUserConfiguration(MetricsAutoConfiguration::class.java)
}

private class CloseableEventStore(
    private val closeCount: AtomicInteger,
) : EventStore by InMemoryEventStore(),
    Closeable {
    override fun close() {
        closeCount.incrementAndGet()
    }
}
