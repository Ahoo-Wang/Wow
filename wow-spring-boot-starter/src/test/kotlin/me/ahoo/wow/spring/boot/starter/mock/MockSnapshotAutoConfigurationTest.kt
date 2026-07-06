package me.ahoo.wow.spring.boot.starter.mock

import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.mock.DelaySnapshotStore
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class MockSnapshotAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with delay snapshot store`() {
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
                    .hasBean("delaySnapshotStore")
                    .hasBean("delaySnapshotRepository")
                    .hasSingleBean(DelaySnapshotStore::class.java)
                    .hasSingleBean(SnapshotStoreBinding::class.java)
                val snapshotStore = context.getBean(DelaySnapshotStore::class.java)
                val binding = context.getBean(SnapshotStoreBinding::class.java)
                binding.storage.assert().isEqualTo(StorageType.DELAY)
                binding.snapshotStore.assert().isSameAs(snapshotStore)
            }
    }
}
