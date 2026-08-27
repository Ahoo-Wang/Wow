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
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

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

    @Test
    fun `should create binding from decorated event store`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.IN_MEMORY_NAME}",
            )
            .withUserConfiguration(
                EventStoreAutoConfiguration::class.java,
                EventStoreDecoratorConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNull()
                val binding = context.getBean(EventStoreBinding::class.java)
                binding.storage.assert().isEqualTo(StorageType.IN_MEMORY)
                binding.eventStore.assert().isInstanceOf(DecoratingEventStore::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class EventStoreDecoratorConfiguration {
        @Bean
        fun eventStoreDecoratorPostProcessor(): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                    if (beanName == "inMemoryEventStore" && bean is EventStore) {
                        return DecoratingEventStore(bean)
                    }
                    return bean
                }
            }
    }

    internal class DecoratingEventStore(
        private val delegate: EventStore
    ) : EventStore {
        override fun append(eventStream: DomainEventStream): Mono<Void> =
            delegate.append(eventStream)

        override fun load(
            aggregateId: AggregateId,
            headVersion: Int,
            tailVersion: Int
        ): Flux<DomainEventStream> =
            delegate.load(aggregateId, headVersion, tailVersion)

        override fun load(
            aggregateId: AggregateId,
            headEventTime: Long,
            tailEventTime: Long
        ): Flux<DomainEventStream> =
            delegate.load(aggregateId, headEventTime, tailEventTime)

        override fun last(aggregateId: AggregateId): Mono<DomainEventStream> =
            delegate.last(aggregateId)
    }
}
