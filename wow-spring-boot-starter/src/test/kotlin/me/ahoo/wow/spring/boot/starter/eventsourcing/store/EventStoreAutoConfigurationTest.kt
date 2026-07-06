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

package me.ahoo.wow.spring.boot.starter.eventsourcing.store

import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class EventStoreAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with in-memory event store`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
            )
            .withUserConfiguration(
                EventStoreAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(InMemoryEventStore::class.java)
                    .hasSingleBean(EventStoreBinding::class.java)
                val eventStore = context.getBean(InMemoryEventStore::class.java)
                val binding = context.getBean(EventStoreBinding::class.java)
                binding.storage.assert().isEqualTo(StorageType.IN_MEMORY)
                binding.eventStore.assert().isSameAs(eventStore)
            }
    }
}
