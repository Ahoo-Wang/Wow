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

package me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot

import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotDispatcher
import me.ahoo.wow.eventsourcing.snapshot.SnapshotFunctionFilter
import me.ahoo.wow.eventsourcing.snapshot.SnapshotHandler
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.spring.boot.starter.MessageBusType
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.event.EventAutoConfiguration
import me.ahoo.wow.spring.boot.starter.event.EventProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreStorage
import me.ahoo.wow.spring.command.SnapshotDispatcherLauncher
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class SnapshotAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${EventStoreStorage.IN_MEMORY_NAME}",
                "${SnapshotProperties.STORAGE}=${SnapshotStorage.IN_MEMORY_NAME}",
                "${EventProperties.Bus.TYPE}=${MessageBusType.IN_MEMORY_NAME}",
            )
            .withUserConfiguration(
                EventAutoConfiguration::class.java,
                EventStoreAutoConfiguration::class.java,
                SnapshotAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(InMemorySnapshotRepository::class.java)
                    .hasSingleBean(SnapshotStrategy::class.java)
                    .hasSingleBean(SnapshotFunctionFilter::class.java)
                    .hasBean("snapshotFilterChain")
                    .hasSingleBean(SnapshotHandler::class.java)
                    .hasSingleBean(SnapshotDispatcher::class.java)
                    .hasSingleBean(SnapshotDispatcherLauncher::class.java)
            }
    }
}
