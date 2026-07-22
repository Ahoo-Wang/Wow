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

package me.ahoo.wow.spring.boot.starter.redis

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.infra.Decorator.Companion.getOriginalDelegate
import me.ahoo.wow.redis.eventsourcing.RedisEventStore
import me.ahoo.wow.redis.eventsourcing.RedisSnapshotStore
import me.ahoo.wow.redis.prepare.RedisPrepareKeyFactory
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import me.ahoo.wow.spring.boot.starter.metrics.MetricsAutoConfiguration
import me.ahoo.wow.spring.boot.starter.prepare.PrepareProperties
import me.ahoo.wow.spring.boot.starter.prepare.PrepareStorage
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import reactor.core.publisher.Mono

class RedisEventSourcingAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with redis event sourcing beans`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${SnapshotProperties.STORAGE}=${StorageType.REDIS_NAME}",
                "${EventStoreProperties.STORAGE}=${StorageType.REDIS_NAME}",
                "${PrepareProperties.STORAGE}=${PrepareStorage.REDIS_NAME}",
            )
            .withBean(ReactiveStringRedisTemplate::class.java, {
                redisTemplate()
            })
            .withUserConfiguration(
                RedisEventSourcingAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(RedisEventStore::class.java)
                    .hasBean("redisSnapshotStore")
                    .doesNotHaveBean("redisSnapshotRepository")
                    .hasSingleBean(RedisSnapshotStore::class.java)
                    .hasSingleBean(EventStoreBinding::class.java)
                    .hasSingleBean(SnapshotStoreBinding::class.java)
                    .hasSingleBean(RedisPrepareKeyFactory::class.java)
                val eventStore = context.getBean(RedisEventStore::class.java)
                val eventBinding = context.getBean(EventStoreBinding::class.java)
                eventBinding.storage.assert().isEqualTo(StorageType.REDIS)
                eventBinding.eventStore.assert().isSameAs(eventStore)

                val snapshotStore = context.getBean(RedisSnapshotStore::class.java)
                val snapshotBinding = context.getBean(SnapshotStoreBinding::class.java)
                snapshotBinding.storage.assert().isEqualTo(StorageType.REDIS)
                snapshotBinding.snapshotStore.assert().isSameAs(snapshotStore)
            }
    }

    @Test
    fun `should load redis event store when aggregate event route uses redis`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.order.event.storage=${StorageType.REDIS_NAME}",
                "wow.context-name=order-service",
            )
            .withBean(ReactiveStringRedisTemplate::class.java, {
                redisTemplate()
            })
            .withBean("mongoEventStoreBinding", EventStoreBinding::class.java, {
                EventStoreBinding.storage(StorageType.MONGO, mockk<EventStore>())
            })
            .withUserConfiguration(
                RedisEventSourcingAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(RedisEventStore::class.java)
                    .hasBean("redisEventStoreBinding")
                    .doesNotHaveBean(RedisSnapshotStore::class.java)
                    .doesNotHaveBean(SnapshotStoreBinding::class.java)
                val eventStore = context.getBean(RedisEventStore::class.java)
                val eventBinding = context.getBean("redisEventStoreBinding", EventStoreBinding::class.java)
                eventBinding.storage.assert().isEqualTo(StorageType.REDIS)
                eventBinding.eventStore.assert().isSameAs(eventStore)
            }
    }

    @Test
    fun `should load redis snapshot store when aggregate snapshot route uses redis`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${SnapshotProperties.STORAGE}=${StorageType.MONGO_NAME}",
                "${StorageRoutingProperties.AGGREGATES}.cart.snapshot.storage=${StorageType.REDIS_NAME}",
            )
            .withBean(ReactiveStringRedisTemplate::class.java, {
                redisTemplate()
            })
            .withUserConfiguration(
                RedisEventSourcingAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .doesNotHaveBean(RedisEventStore::class.java)
                    .doesNotHaveBean(EventStoreBinding::class.java)
                    .hasSingleBean(RedisSnapshotStore::class.java)
                    .hasSingleBean(SnapshotStoreBinding::class.java)
                val snapshotStore = context.getBean(RedisSnapshotStore::class.java)
                val snapshotBinding = context.getBean(SnapshotStoreBinding::class.java)
                snapshotBinding.storage.assert().isEqualTo(StorageType.REDIS)
                snapshotBinding.snapshotStore.assert().isSameAs(snapshotStore)
            }
    }

    @Test
    fun `should fail startup when an incompatible Redis EventStore layout exists`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.REDIS_NAME}",
                "wow.context-name=order-service",
            )
            .withBean(ReactiveStringRedisTemplate::class.java, {
                redisTemplate(setOf("order-service.order:es:req_idx"))
            })
            .withUserConfiguration(RedisEventSourcingAutoConfiguration::class.java)
            .run { context ->
                context.startupFailure.assert().isNotNull()
                generateSequence(context.startupFailure) { error -> error.cause }
                    .mapNotNull(Throwable::message)
                    .toList()
                    .assert()
                    .anyMatch { message ->
                        message.contains("canonical v2 only") &&
                            message.contains("order-service.order:es:req_idx") &&
                            message.contains("will neither read nor migrate incompatible data")
                    }
            }
    }

    @Test
    fun `should fail startup when a bucketed legacy aggregate index exists`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.REDIS_NAME}",
                "wow.context-name=order-service",
            )
            .withBean(ReactiveStringRedisTemplate::class.java, {
                redisTemplate(setOf("{order-service.order:es:127}:ids"))
            })
            .withUserConfiguration(RedisEventSourcingAutoConfiguration::class.java)
            .run { context ->
                context.startupFailure.assert().isNotNull()
                failureMessages(context.startupFailure).assert()
                    .anyMatch { message ->
                        message.contains("BUCKETED_AGGREGATE_ID_INDEX") &&
                            message.contains("{order-service.order:es:127}:ids")
                    }
            }
    }

    @Test
    fun `should check exact shared and bucketed legacy sentinels`() {
        val checkedKeys = mutableListOf<String>()
        val redisTemplate = mockk<ReactiveStringRedisTemplate> {
            every { hasKey(capture(checkedKeys)) } returns Mono.just(false)
        }
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.REDIS_NAME}",
                "wow.context-name=order-service",
            )
            .withBean(ReactiveStringRedisTemplate::class.java, { redisTemplate })
            .withUserConfiguration(RedisEventSourcingAutoConfiguration::class.java)
            .run { context ->
                context.startupFailure.assert().isNull()
            }

        checkedKeys.assert().contains("order-service.order:es:req_idx")
        checkedKeys.assert().contains("{order-service.order:es:0}:ids")
        checkedKeys.assert().contains("{order-service.order:es:127}:ids")
    }

    @Test
    fun `should fail closed when Redis layout check fails`() {
        val redisFailure = IllegalStateException("Redis unavailable")
        val redisTemplate = mockk<ReactiveStringRedisTemplate> {
            every { hasKey(any()) } returns Mono.error(redisFailure)
        }
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.REDIS_NAME}",
                "wow.context-name=order-service",
            )
            .withBean(ReactiveStringRedisTemplate::class.java, { redisTemplate })
            .withUserConfiguration(RedisEventSourcingAutoConfiguration::class.java)
            .run { context ->
                context.startupFailure.assert().isNotNull()
                failureMessages(context.startupFailure).assert()
                    .anyMatch { message ->
                        message.contains("layout check failed closed") &&
                            message.contains("Startup cannot safely continue")
                    }
            }
    }

    @Test
    fun `should fail closed when Redis returns no key existence result`() {
        val redisTemplate = mockk<ReactiveStringRedisTemplate> {
            every { hasKey(any()) } returns Mono.empty()
        }
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.REDIS_NAME}",
                "wow.context-name=order-service",
            )
            .withBean(ReactiveStringRedisTemplate::class.java, { redisTemplate })
            .withUserConfiguration(RedisEventSourcingAutoConfiguration::class.java)
            .run { context ->
                context.startupFailure.assert().isNotNull()
                failureMessages(context.startupFailure).assert()
                    .anyMatch { message -> message.contains("returned no result for key") }
            }
    }

    @Test
    fun `should run the layout guard when metrics decorates the Redis EventStore`() {
        val redisTemplate = redisTemplate()
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${EventStoreProperties.STORAGE}=${StorageType.REDIS_NAME}",
                "wow.context-name=order-service",
            )
            .withBean(ReactiveStringRedisTemplate::class.java, {
                redisTemplate
            })
            .withUserConfiguration(
                MetricsAutoConfiguration::class.java,
                RedisEventSourcingAutoConfiguration::class.java,
            )
            .run { context ->
                context.assert()
                    .hasBean("redisEventStoreLayoutGuard")
                val eventStore = context.getBean("redisEventStore", EventStore::class.java)
                eventStore.getOriginalDelegate().assert().isInstanceOf(RedisEventStore::class.java)
            }
        verify(atLeast = 1) { redisTemplate.hasKey(any()) }
    }

    private fun redisTemplate(existingKeys: Set<String> = emptySet()): ReactiveStringRedisTemplate =
        mockk {
            every { hasKey(any()) } answers {
                Mono.just(firstArg<String>() in existingKeys)
            }
        }

    private fun failureMessages(failure: Throwable?): List<String> =
        generateSequence(failure) { error -> error.cause }
            .mapNotNull(Throwable::message)
            .toList()
}
