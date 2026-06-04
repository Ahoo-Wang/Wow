package me.ahoo.wow.spring.boot.starter.mock

import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.mock.DelaySnapshotRepository
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class MockSnapshotAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with delay snapshot repository`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${SnapshotProperties.STORAGE}=${StorageType.DELAY_NAME}",
            )
            .withUserConfiguration(
                MockSnapshotAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(DelaySnapshotRepository::class.java)
            }
    }
}
