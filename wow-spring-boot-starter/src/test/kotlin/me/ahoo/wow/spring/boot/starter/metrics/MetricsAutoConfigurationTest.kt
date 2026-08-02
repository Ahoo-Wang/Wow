package me.ahoo.wow.spring.boot.starter.metrics

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.metrics.MetricEventStore
import me.ahoo.wow.metrics.MetricSnapshotStore
import me.ahoo.wow.metrics.Metrics
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

class MetricsAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

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
    fun `metrics post processor should preserve inferred event store destruction`() {
        val closeCount = AtomicInteger()

        contextRunner
            .enableWow()
            .withBean(
                "closeableEventStore",
                EventStore::class.java,
                { CloseableEventStore(closeCount) },
            )
            .withUserConfiguration(MetricsAutoConfiguration::class.java)
            .run { context ->
                context.getBean("closeableEventStore")
                    .assert()
                    .isInstanceOf(MetricEventStore::class.java)
                closeCount.get().assert().isEqualTo(0)
            }

        closeCount.get().assert().isEqualTo(1)
    }

    @Test
    fun `metrics post processor should decorate raw snapshot store binding once`() {
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

        metricBinding.snapshotStore.assert().isInstanceOf(MetricSnapshotStore::class.java)
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
        val previousEnabled = Metrics.enabled

        contextRunner
            .enableWow()
            .withPropertyValues(
                "${ConditionalOnMetricsEnabled.ENABLED_KEY}=false",
            )
            .withBean(
                "metricsEnabledAtBeanCreation",
                Boolean::class.javaObjectType,
                { Metrics.enabled },
            )
            .withUserConfiguration(
                MetricsAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .doesNotHaveBean(MetricsBeanPostProcessor::class.java)
                context.getBean("metricsEnabledAtBeanCreation", Boolean::class.java)
                    .assert()
                    .isFalse()
            }

        Metrics.enabled.assert().isEqualTo(previousEnabled)
    }

    @Test
    fun `metrics switch should use the same strict truth parsing as its condition`() {
        val previousEnabled = Metrics.enabled

        contextRunner
            .enableWow()
            .withPropertyValues(
                "${ConditionalOnMetricsEnabled.ENABLED_KEY}=on",
            ).withBean(
                "metricsEnabledAtBeanCreation",
                Boolean::class.javaObjectType,
                { Metrics.enabled },
            ).withUserConfiguration(
                MetricsAutoConfiguration::class.java,
            ).run { context: AssertableApplicationContext ->
                context.assert()
                    .doesNotHaveBean(MetricsBeanPostProcessor::class.java)
                context.getBean("metricsEnabledAtBeanCreation", Boolean::class.java)
                    .assert()
                    .isFalse()
            }

        Metrics.enabled.assert().isEqualTo(previousEnabled)
    }

    @Test
    fun `metrics should remain disabled until every disabling context closes`() {
        val previousEnabled = Metrics.enabled
        val disabledContextRunner = contextRunner
            .enableWow()
            .withPropertyValues(
                "${ConditionalOnMetricsEnabled.ENABLED_KEY}=false",
            ).withUserConfiguration(
                MetricsAutoConfiguration::class.java,
            )

        disabledContextRunner.run { firstContext ->
            disabledContextRunner.run {
                firstContext.close()

                Metrics.enabled.assert().isFalse()
            }
        }

        Metrics.enabled.assert().isEqualTo(previousEnabled)
    }

    @Test
    fun `metrics synchronizer destruction should be idempotent`() {
        val previousEnabled = Metrics.enabled
        val synchronizer = MetricsEnabledSynchronizer(enabled = false)

        try {
            Metrics.enabled.assert().isFalse()
        } finally {
            synchronizer.destroy()
            synchronizer.destroy()
        }

        Metrics.enabled.assert().isEqualTo(previousEnabled)
    }

    @Test
    fun `conflicting metrics contexts should fail instead of partially instrumenting`() {
        val previousEnabled = Metrics.enabled

        metricsContextRunner(enabled = false).run {
            metricsContextRunner(enabled = true).run { conflictingContext ->
                conflictingContext.assert().hasFailed()
                Metrics.enabled.assert().isFalse()
            }
        }
        Metrics.enabled.assert().isEqualTo(previousEnabled)

        metricsContextRunner(enabled = true).run {
            metricsContextRunner(enabled = false).run { conflictingContext ->
                conflictingContext.assert().hasFailed()
                Metrics.enabled.assert().isTrue()
            }
        }
        Metrics.enabled.assert().isEqualTo(previousEnabled)
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

    private fun metricsContextRunner(enabled: Boolean): ApplicationContextRunner =
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${ConditionalOnMetricsEnabled.ENABLED_KEY}=$enabled",
            ).withUserConfiguration(
                MetricsAutoConfiguration::class.java,
            )
}

private class CloseableEventStore(
    private val closeCount: AtomicInteger,
) : EventStore by InMemoryEventStore(),
    Closeable {
    override fun close() {
        closeCount.incrementAndGet()
    }
}
