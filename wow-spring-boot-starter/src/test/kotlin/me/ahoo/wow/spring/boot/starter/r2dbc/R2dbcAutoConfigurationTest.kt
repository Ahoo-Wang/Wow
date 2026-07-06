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

package me.ahoo.wow.spring.boot.starter.r2dbc

import io.mockk.mockk
import io.r2dbc.spi.ConnectionFactory
import me.ahoo.test.asserts.assert
import me.ahoo.wow.r2dbc.R2dbcEventStore
import me.ahoo.wow.r2dbc.R2dbcSnapshotStore
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class R2dbcAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with r2dbc event store and snapshot store`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${SnapshotProperties.STORAGE}=${StorageType.R2DBC_NAME}",
                "${EventStoreProperties.STORAGE}=${StorageType.R2DBC_NAME}",
            )
            .withBean(ConnectionFactory::class.java, { mockk() })
            .withUserConfiguration(
                R2dbcAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(R2dbcEventStore::class.java)
                    .hasBean("r2dbcSnapshotStore")
                    .hasBean("r2dbcSnapshotRepository")
                    .hasSingleBean(R2dbcSnapshotStore::class.java)
                    .hasSingleBean(EventStoreBinding::class.java)
                    .hasSingleBean(SnapshotStoreBinding::class.java)
                val eventStore = context.getBean(R2dbcEventStore::class.java)
                val eventBinding = context.getBean(EventStoreBinding::class.java)
                eventBinding.storage.assert().isEqualTo(StorageType.R2DBC)
                eventBinding.eventStore.assert().isSameAs(eventStore)

                val snapshotStore = context.getBean(R2dbcSnapshotStore::class.java)
                val snapshotBinding = context.getBean(SnapshotStoreBinding::class.java)
                snapshotBinding.storage.assert().isEqualTo(StorageType.R2DBC)
                snapshotBinding.snapshotStore.assert().isSameAs(snapshotStore)
            }
    }
}
