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

package me.ahoo.wow.spring.boot.starter.opentelemetry

import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.AggregateEventStoreRegistry
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.RoutingEventStore
import me.ahoo.wow.eventsourcing.snapshot.AggregateSnapshotStoreRegistry
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.RoutingSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.VersionedSnapshotStore
import me.ahoo.wow.opentelemetry.aggregate.TraceAggregateFilter
import me.ahoo.wow.opentelemetry.eventprocessor.TraceEventProcessorFilter
import me.ahoo.wow.opentelemetry.projection.TraceProjectionFilter
import me.ahoo.wow.opentelemetry.saga.TraceStatelessSagaFilter
import me.ahoo.wow.opentelemetry.snapshot.TraceSnapshotFilter
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.metrics.MetricsBeanPostProcessor
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class WowOpenTelemetryAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with open telemetry trace beans`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(
                WowOpenTelemetryAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(TraceAggregateFilter::class.java)
                    .hasSingleBean(TraceProjectionFilter::class.java)
                    .hasSingleBean(TraceSnapshotFilter::class.java)
                    .hasSingleBean(TraceStatelessSagaFilter::class.java)
                    .hasSingleBean(TraceEventProcessorFilter::class.java)
                    .hasSingleBean(TracingBeanPostProcessor::class.java)
            }
    }

    @Test
    fun `should disable open telemetry`() {
        contextRunner
            .enableWow()
            .withPropertyValues("${ConditionalOnOpenTelemetryEnabled.ENABLED_KEY}=false")
            .withUserConfiguration(
                WowOpenTelemetryAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .doesNotHaveBean(TraceAggregateFilter::class.java)
                    .doesNotHaveBean(TraceProjectionFilter::class.java)
                    .doesNotHaveBean(TraceSnapshotFilter::class.java)
                    .doesNotHaveBean(TraceStatelessSagaFilter::class.java)
                    .doesNotHaveBean(TraceEventProcessorFilter::class.java)
                    .doesNotHaveBean(TracingBeanPostProcessor::class.java)
            }
    }

    @Test
    fun `should back off when custom trace filter exists`() {
        contextRunner
            .enableWow()
            .withBean("customTraceSnapshotFilter", TraceSnapshotFilter::class.java, { TraceSnapshotFilter })
            .withUserConfiguration(
                WowOpenTelemetryAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert().hasSingleBean(TraceSnapshotFilter::class.java)
            }
    }

    @Test
    fun `should preserve versioned snapshot store capability`() {
        val tracedStore = TracingBeanPostProcessor().postProcessAfterInitialization(
            InMemorySnapshotStore(),
            "snapshotStore",
        )
        val metricStore = MetricsBeanPostProcessor().postProcessAfterInitialization(
            tracedStore,
            "snapshotStore",
        )

        tracedStore.assert().isInstanceOf(VersionedSnapshotStore::class.java)
        metricStore.assert().isInstanceOf(VersionedSnapshotStore::class.java)
    }

    @Test
    fun `metrics should keep traced routing event store transparent`() {
        val routingStore = RoutingEventStore(
            AggregateEventStoreRegistry(
                defaultEventStore = InMemoryEventStore(),
                routes = emptyMap(),
            ),
        )
        val tracedStore = TracingBeanPostProcessor().postProcessAfterInitialization(
            routingStore,
            "eventStore",
        )

        val metricStore = MetricsBeanPostProcessor().postProcessAfterInitialization(
            tracedStore,
            "eventStore",
        )

        metricStore.assert().isSameAs(tracedStore)
    }

    @Test
    fun `metrics should keep traced routing snapshot store transparent`() {
        val routingStore = RoutingSnapshotStore.create(
            AggregateSnapshotStoreRegistry(
                defaultSnapshotStore = InMemorySnapshotStore(),
                routes = emptyMap(),
            ),
        )
        val tracedStore = TracingBeanPostProcessor().postProcessAfterInitialization(
            routingStore,
            "snapshotStore",
        )

        val metricStore = MetricsBeanPostProcessor().postProcessAfterInitialization(
            tracedStore,
            "snapshotStore",
        )

        metricStore.assert().isSameAs(tracedStore)
        metricStore.assert().isInstanceOf(VersionedSnapshotStore::class.java)
    }
}
