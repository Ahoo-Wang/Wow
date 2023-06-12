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

package me.ahoo.wow.spring.boot.starter.event

import io.mockk.mockk
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.event.LocalDomainEventBus
import me.ahoo.wow.event.LocalFirstDomainEventBus
import me.ahoo.wow.event.NoOpDomainEventBus
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.spring.boot.starter.BusProperties
import me.ahoo.wow.spring.boot.starter.enableWow
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class EventAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withPropertyValues("${EventProperties.BUS_TYPE}=${BusProperties.Type.IN_MEMORY_NAME}")
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withUserConfiguration(
                EventAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(InMemoryDomainEventBus::class.java)
                    .hasSingleBean(DomainEventCompensator::class.java)
            }
    }

    @Test
    fun contextLoadsIfNoOp() {
        contextRunner
            .enableWow()
            .withPropertyValues("${EventProperties.BUS_TYPE}=${BusProperties.Type.NO_OP}")
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withUserConfiguration(
                EventAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(NoOpDomainEventBus::class.java)
                    .hasSingleBean(DomainEventCompensator::class.java)
            }
    }

    @Test
    fun contextLoadsIfLocalFirst() {
        contextRunner
            .enableWow()
            .withBean(EventStore::class.java, { mockk() })
            .withBean(DistributedDomainEventBus::class.java, { mockk() })
            .withUserConfiguration(
                EventAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(LocalDomainEventBus::class.java)
                    .hasSingleBean(LocalFirstDomainEventBus::class.java)
            }
    }
}
