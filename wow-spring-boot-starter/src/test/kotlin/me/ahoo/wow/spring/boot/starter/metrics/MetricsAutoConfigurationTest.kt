package me.ahoo.wow.spring.boot.starter.metrics

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.VersionedSnapshotStore
import me.ahoo.wow.spring.boot.starter.enableWow
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
}
