package me.ahoo.wow.spring.boot.starter.mock

import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.mock.DelayEventStore
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class MockEventStoreAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with delay event store`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.DELAY_NAME}",
            )
            .withUserConfiguration(
                MockEventStoreAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(DelayEventStore::class.java)
            }
    }
}
