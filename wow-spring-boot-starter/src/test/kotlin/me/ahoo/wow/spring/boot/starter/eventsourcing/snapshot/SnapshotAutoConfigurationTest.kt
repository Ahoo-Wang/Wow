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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.snapshot.CompositeSnapshotStrategy
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshotStrategy
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.VersionOffsetSnapshotStrategy
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotDispatcher
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotFunctionFilter
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotHandler
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.spring.boot.starter.BusType
import me.ahoo.wow.spring.boot.starter.WowProperties
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.event.EventAutoConfiguration
import me.ahoo.wow.spring.boot.starter.event.EventProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import me.ahoo.wow.spring.command.SnapshotDispatcherLauncher
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Mono

internal class SnapshotAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `legacy constructor keeps checkpoints disabled by default`() {
        val configuration = SnapshotAutoConfiguration(
            wowProperties = WowProperties(contextName = "test"),
            snapshotProperties = SnapshotProperties(),
        )
        val snapshotStore = configuration.inMemorySnapshotStore()

        configuration.simpleSnapshotStrategy(snapshotStore).assert()
            .isInstanceOf(SimpleSnapshotStrategy::class.java)
    }

    @Test
    fun `should load context with snapshot beans`() {
        contextRunner
            .enableWow()
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(StateEventBus::class.java, { InMemoryStateEventBus() })
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "${EventProperties.BUS_TYPE}=${BusType.IN_MEMORY_NAME}",
            )
            .withUserConfiguration(
                EventAutoConfiguration::class.java,
                EventStoreAutoConfiguration::class.java,
                SnapshotAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasBean("inMemorySnapshotStore")
                    .hasBean("inMemorySnapshotRepository")
                    .hasSingleBean(InMemorySnapshotStore::class.java)
                    .hasSingleBean(SimpleSnapshotStrategy::class.java)
                    .hasSingleBean(SnapshotFunctionFilter::class.java)
                    .hasBean("snapshotFilterChain")
                    .hasSingleBean(SnapshotHandler::class.java)
                    .hasSingleBean(SnapshotDispatcher::class.java)
                    .hasSingleBean(SnapshotDispatcherLauncher::class.java)
                    .hasSingleBean(SnapshotStoreBinding::class.java)
                val snapshotStore = context.getBean(InMemorySnapshotStore::class.java)
                val binding = context.getBean(SnapshotStoreBinding::class.java)
                binding.storage.assert().isEqualTo(StorageType.IN_MEMORY)
                binding.snapshotStore.assert().isSameAs(snapshotStore)
            }
    }

    @Test
    fun `should create binding from decorated snapshot store`() {
        contextRunner
            .enableWow()
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(StateEventBus::class.java, { InMemoryStateEventBus() })
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "${EventProperties.BUS_TYPE}=${BusType.IN_MEMORY_NAME}",
            )
            .withUserConfiguration(
                EventAutoConfiguration::class.java,
                EventStoreAutoConfiguration::class.java,
                SnapshotAutoConfiguration::class.java,
                SnapshotStoreDecoratorConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNull()
                val binding = context.getBean(SnapshotStoreBinding::class.java)
                binding.storage.assert().isEqualTo(StorageType.IN_MEMORY)
                binding.snapshotStore.assert().isInstanceOf(DecoratingSnapshotStore::class.java)
            }
    }

    @Test
    fun `should load context when version offset snapshot strategy`() {
        contextRunner
            .enableWow()
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(StateEventBus::class.java, { InMemoryStateEventBus() })
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "${SnapshotProperties.STRATEGY}=${Strategy.VERSION_OFFSET_NAME}",
                "${EventProperties.BUS_TYPE}=${BusType.IN_MEMORY_NAME}",
            )
            .withUserConfiguration(
                EventAutoConfiguration::class.java,
                EventStoreAutoConfiguration::class.java,
                SnapshotAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasBean("inMemorySnapshotStore")
                    .hasBean("inMemorySnapshotRepository")
                    .hasSingleBean(InMemorySnapshotStore::class.java)
                    .hasSingleBean(VersionOffsetSnapshotStrategy::class.java)
                    .hasSingleBean(SnapshotFunctionFilter::class.java)
                    .hasBean("snapshotFilterChain")
                    .hasSingleBean(SnapshotHandler::class.java)
                    .hasSingleBean(SnapshotDispatcher::class.java)
                    .hasSingleBean(SnapshotDispatcherLauncher::class.java)
                    .hasSingleBean(SnapshotStoreBinding::class.java)
                val snapshotStore = context.getBean(InMemorySnapshotStore::class.java)
                val binding = context.getBean(SnapshotStoreBinding::class.java)
                binding.storage.assert().isEqualTo(StorageType.IN_MEMORY)
                binding.snapshotStore.assert().isSameAs(snapshotStore)
            }
    }

    @Test
    fun `should compose immutable checkpoint strategy when explicitly enabled`() {
        contextRunner
            .enableWow()
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(StateEventBus::class.java, { InMemoryStateEventBus() })
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "${SnapshotCheckpointProperties.PREFIX}.enabled=true",
                "${SnapshotCheckpointProperties.PREFIX}.version-interval=25",
                "${EventProperties.BUS_TYPE}=${BusType.IN_MEMORY_NAME}",
            )
            .withUserConfiguration(
                EventAutoConfiguration::class.java,
                EventStoreAutoConfiguration::class.java,
                SnapshotAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasNotFailed()
                    .hasSingleBean(CompositeSnapshotStrategy::class.java)
                val checkpoint = context.getBean(SnapshotCheckpointProperties::class.java)
                checkpoint.enabled.assert().isTrue()
                checkpoint.versionInterval.assert().isEqualTo(25)
            }
    }

    @Test
    fun `should reject a non-positive checkpoint interval during binding`() {
        contextRunner
            .enableWow()
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(StateEventBus::class.java, { InMemoryStateEventBus() })
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "${SnapshotCheckpointProperties.PREFIX}.enabled=true",
                "${SnapshotCheckpointProperties.PREFIX}.version-interval=0",
                "${EventProperties.BUS_TYPE}=${BusType.IN_MEMORY_NAME}",
            )
            .withUserConfiguration(
                EventAutoConfiguration::class.java,
                EventStoreAutoConfiguration::class.java,
                SnapshotAutoConfiguration::class.java,
            )
            .run { context ->
                context.startupFailure.assert().isNotNull()
            }
    }

    @Test
    fun `should fail fast when the selected snapshot store lacks checkpoint support`() {
        contextRunner
            .enableWow()
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(StateEventBus::class.java, { InMemoryStateEventBus() })
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
                "${SnapshotCheckpointProperties.PREFIX}.enabled=true",
                "${EventProperties.BUS_TYPE}=${BusType.IN_MEMORY_NAME}",
            )
            .withUserConfiguration(
                EventAutoConfiguration::class.java,
                EventStoreAutoConfiguration::class.java,
                SnapshotAutoConfiguration::class.java,
                SnapshotStoreDecoratorConfiguration::class.java,
            )
            .run { context ->
                context.startupFailure.assert().isNotNull()
                generateSequence(context.startupFailure) { error -> error.cause }
                    .mapNotNull(Throwable::message)
                    .toList()
                    .assert()
                    .anyMatch {
                        it.contains("does not support historical checkpoints") &&
                            it.contains("${SnapshotCheckpointProperties.PREFIX}.enabled=true")
                    }
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class SnapshotStoreDecoratorConfiguration {
        @Bean
        fun snapshotStoreDecoratorPostProcessor(): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                    if (beanName == "inMemorySnapshotStore" && bean is SnapshotStore) {
                        return DecoratingSnapshotStore(bean)
                    }
                    return bean
                }
            }
    }

    internal class DecoratingSnapshotStore(
        private val delegate: SnapshotStore
    ) : SnapshotStore {
        override val name: String
            get() = delegate.name

        override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> =
            delegate.load(aggregateId)

        override fun getVersion(aggregateId: AggregateId): Mono<Int> =
            delegate.getVersion(aggregateId)

        override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> =
            delegate.save(snapshot)
    }
}
