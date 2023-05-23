package me.ahoo.wow.spring.boot.starter.mock

import me.ahoo.wow.eventsourcing.mock.DelaySnapshotRepository
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotStorage
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class MockSnapshotAutoConfigurationTest{
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${SnapshotProperties.STORAGE}=${SnapshotStorage.DELAY_NAME}",
            )
            .withUserConfiguration(
                MockSnapshotAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(DelaySnapshotRepository::class.java)
            }
    }
}