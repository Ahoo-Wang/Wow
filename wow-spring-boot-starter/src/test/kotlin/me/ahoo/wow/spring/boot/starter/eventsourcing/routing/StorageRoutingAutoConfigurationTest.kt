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
package me.ahoo.wow.spring.boot.starter.eventsourcing.routing

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.ConditionalOnSnapshotEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class StorageRoutingAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(StorageConditionConfiguration::class.java)

    @Test
    fun `default storage should match mongo conditions`() {
        contextRunner
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasBean(MONGO_EVENT_STORE_BEAN)
                    .hasBean(MONGO_SNAPSHOT_STORE_BEAN)
            }
    }

    @Test
    fun `global event storage should match event condition`() {
        contextRunner
            .withPropertyValues("${EventStoreProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}")
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasBean(IN_MEMORY_EVENT_STORE_BEAN)
                context.containsBean(IN_MEMORY_SNAPSHOT_STORE_BEAN).assert().isFalse()
            }
    }

    @Test
    fun `event route storage should match event condition when global event storage differs`() {
        contextRunner
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.order.event.storage=${StorageType.REDIS_NAME}",
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasBean(REDIS_EVENT_STORE_BEAN)
                context.containsBean(REDIS_SNAPSHOT_STORE_BEAN).assert().isFalse()
            }
    }

    @Test
    fun `snapshot route storage should match snapshot condition when global snapshot storage differs`() {
        contextRunner
            .withPropertyValues(
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.cart.snapshot.storage=${StorageType.REDIS_NAME}",
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasBean(REDIS_SNAPSHOT_STORE_BEAN)
                context.containsBean(REDIS_EVENT_STORE_BEAN).assert().isFalse()
            }
    }

    @Test
    fun `event route storage should not match snapshot condition`() {
        contextRunner
            .withPropertyValues(
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.order.event.storage=${StorageType.REDIS_NAME}",
            )
            .run { context: AssertableApplicationContext ->
                context.containsBean(REDIS_SNAPSHOT_STORE_BEAN).assert().isFalse()
            }
    }

    @Test
    fun `snapshot route storage should not match event condition`() {
        contextRunner
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.cart.snapshot.storage=${StorageType.REDIS_NAME}",
            )
            .run { context: AssertableApplicationContext ->
                context.containsBean(REDIS_EVENT_STORE_BEAN).assert().isFalse()
            }
    }

    @Test
    fun `snapshot storage should not match when snapshot is disabled`() {
        contextRunner
            .withPropertyValues(
                "${ConditionalOnSnapshotEnabled.ENABLED_KEY}=false",
                "${SnapshotProperties.STORAGE}=${StorageType.REDIS_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.cart.snapshot.storage=${StorageType.IN_MEMORY_NAME}",
            )
            .run { context: AssertableApplicationContext ->
                context.containsBean(REDIS_SNAPSHOT_STORE_BEAN).assert().isFalse()
                context.containsBean(IN_MEMORY_SNAPSHOT_STORE_BEAN).assert().isFalse()
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class StorageConditionConfiguration {
        @Bean(MONGO_EVENT_STORE_BEAN)
        @ConditionalOnEventStoreStorage(StorageType.MONGO)
        fun mongoEventStoreBackend(): String = MONGO_EVENT_STORE_BEAN

        @Bean(IN_MEMORY_EVENT_STORE_BEAN)
        @ConditionalOnEventStoreStorage(StorageType.IN_MEMORY)
        fun inMemoryEventStoreBackend(): String = IN_MEMORY_EVENT_STORE_BEAN

        @Bean(REDIS_EVENT_STORE_BEAN)
        @ConditionalOnEventStoreStorage(StorageType.REDIS)
        fun redisEventStoreBackend(): String = REDIS_EVENT_STORE_BEAN

        @Bean(MONGO_SNAPSHOT_STORE_BEAN)
        @ConditionalOnSnapshotStoreStorage(StorageType.MONGO)
        fun mongoSnapshotStoreBackend(): String = MONGO_SNAPSHOT_STORE_BEAN

        @Bean(IN_MEMORY_SNAPSHOT_STORE_BEAN)
        @ConditionalOnSnapshotStoreStorage(StorageType.IN_MEMORY)
        fun inMemorySnapshotStoreBackend(): String = IN_MEMORY_SNAPSHOT_STORE_BEAN

        @Bean(REDIS_SNAPSHOT_STORE_BEAN)
        @ConditionalOnSnapshotStoreStorage(StorageType.REDIS)
        fun redisSnapshotStoreBackend(): String = REDIS_SNAPSHOT_STORE_BEAN
    }

    companion object {
        private const val MONGO_EVENT_STORE_BEAN = "mongoEventStoreBackend"
        private const val IN_MEMORY_EVENT_STORE_BEAN = "inMemoryEventStoreBackend"
        private const val REDIS_EVENT_STORE_BEAN = "redisEventStoreBackend"
        private const val MONGO_SNAPSHOT_STORE_BEAN = "mongoSnapshotStoreBackend"
        private const val IN_MEMORY_SNAPSHOT_STORE_BEAN = "inMemorySnapshotStoreBackend"
        private const val REDIS_SNAPSHOT_STORE_BEAN = "redisSnapshotStoreBackend"
    }
}
