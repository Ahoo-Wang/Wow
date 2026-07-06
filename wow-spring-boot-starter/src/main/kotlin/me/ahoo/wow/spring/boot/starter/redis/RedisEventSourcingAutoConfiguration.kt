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

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.infra.prepare.PrepareKeyFactory
import me.ahoo.wow.redis.eventsourcing.RedisEventStore
import me.ahoo.wow.redis.eventsourcing.RedisSnapshotStore
import me.ahoo.wow.redis.prepare.RedisPrepareKeyFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnEventStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnSnapshotStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.ConditionalOnSnapshotEnabled
import me.ahoo.wow.spring.boot.starter.prepare.ConditionalOnPrepareEnabled
import me.ahoo.wow.spring.boot.starter.prepare.PrepareProperties
import me.ahoo.wow.spring.boot.starter.prepare.PrepareStorage
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

@AutoConfiguration(after = [DataRedisReactiveAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnRedisEnabled
@ConditionalOnClass(RedisEventStore::class)
@EnableConfigurationProperties(RedisProperties::class)
class RedisEventSourcingAutoConfiguration {

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.REDIS)
    fun redisEventStore(redisTemplate: ReactiveStringRedisTemplate): RedisEventStore {
        return RedisEventStore(redisTemplate)
    }

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.REDIS)
    fun redisEventStoreBinding(
        @Qualifier("redisEventStore")
        eventStore: EventStore
    ): EventStoreBinding {
        return EventStoreBinding.storage(StorageType.REDIS, eventStore)
    }

    @Bean(name = ["redisSnapshotStore", "redisSnapshotRepository"])
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.REDIS)
    fun redisSnapshotStore(redisTemplate: ReactiveStringRedisTemplate): RedisSnapshotStore {
        return RedisSnapshotStore(redisTemplate)
    }

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.REDIS)
    fun redisSnapshotStoreBinding(
        @Qualifier("redisSnapshotStore")
        snapshotStore: SnapshotStore
    ): SnapshotStoreBinding {
        return SnapshotStoreBinding.storage(StorageType.REDIS, snapshotStore)
    }

    @Bean
    @ConditionalOnProperty(
        PrepareProperties.STORAGE,
        havingValue = PrepareStorage.REDIS_NAME,
    )
    @ConditionalOnPrepareEnabled
    @ConditionalOnMissingBean
    fun redisPrepareKeyFactory(redisTemplate: ReactiveStringRedisTemplate): PrepareKeyFactory {
        return RedisPrepareKeyFactory(redisTemplate)
    }
}
