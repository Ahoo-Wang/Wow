package me.ahoo.wow.spring.boot.starter.metrics

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.VersionedSnapshotStore
import me.ahoo.wow.metrics.MetricEventStore
import me.ahoo.wow.metrics.MetricVersionedSnapshotStore
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class MetricsAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `metrics post processor should preserve snapshot checkpoint capability`() {
        val postProcessor = MetricsBeanPostProcessor()

        val legacy = postProcessor.postProcessAfterInitialization(mockk<SnapshotStore>(), "legacySnapshotStore")
        val versioned = postProcessor.postProcessAfterInitialization(NoOpSnapshotStore, "versionedSnapshotStore")

        (legacy is VersionedSnapshotStore).assert().isFalse()
        (versioned is VersionedSnapshotStore).assert().isTrue()
    }

    @Test
    fun `metrics post processor should decorate raw event store binding once`() {
        val binding = EventStoreBinding(
            name = "custom-event-store",
            storage = null,
            eventStore = mockk<EventStore>(),
        )
        val postProcessor = MetricsBeanPostProcessor()

        val metricBinding = postProcessor
            .postProcessAfterInitialization(binding, "customEventStoreBinding") as EventStoreBinding
        val processedAgain = postProcessor
            .postProcessAfterInitialization(metricBinding, "customEventStoreBinding")

        metricBinding.eventStore.assert().isInstanceOf(MetricEventStore::class.java)
        processedAgain.assert().isSameAs(metricBinding)
    }

    @Test
    fun `metrics post processor should decorate raw versioned snapshot store binding once`() {
        val binding = SnapshotStoreBinding(
            name = "custom-snapshot-store",
            storage = null,
            snapshotStore = InMemorySnapshotStore(),
        )
        val postProcessor = MetricsBeanPostProcessor()

        val metricBinding = postProcessor
            .postProcessAfterInitialization(binding, "customSnapshotStoreBinding") as SnapshotStoreBinding
        val processedAgain = postProcessor
            .postProcessAfterInitialization(metricBinding, "customSnapshotStoreBinding")

        metricBinding.snapshotStore.assert().isInstanceOf(MetricVersionedSnapshotStore::class.java)
        processedAgain.assert().isSameAs(metricBinding)
    }

    @Test
    fun `should load context with metrics bean post processor`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(
                MetricsAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(MetricsBeanPostProcessor::class.java)
            }
    }

    @Test
    fun `should not load metrics bean when disabled`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${ConditionalOnMetricsEnabled.ENABLED_KEY}=false",
            )
            .withUserConfiguration(
                MetricsAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .doesNotHaveBean(MetricsBeanPostProcessor::class.java)
            }
    }

    @Test
    fun `should back off when custom metrics post processor exists`() {
        contextRunner
            .enableWow()
            .withBean(
                "customMetricsBeanPostProcessor",
                MetricsBeanPostProcessor::class.java,
                { MetricsBeanPostProcessor() },
            )
            .withUserConfiguration(MetricsAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert().hasSingleBean(MetricsBeanPostProcessor::class.java)
            }
    }
}
