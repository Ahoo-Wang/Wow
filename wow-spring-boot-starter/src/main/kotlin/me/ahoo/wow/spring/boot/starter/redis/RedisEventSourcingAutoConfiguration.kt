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
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.redis.RedisEventStore
import me.ahoo.wow.redis.RedisSnapshotRepository
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.ConditionalOnSnapshotEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreStorage
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

@AutoConfiguration(after = [RedisReactiveAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnRedisEnabled
@ConditionalOnClass(RedisEventStore::class)
@EnableConfigurationProperties(RedisProperties::class)
class RedisEventSourcingAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
        EventStoreProperties.STORAGE,
        havingValue = EventStoreStorage.REDIS_NAME,
    )
    fun redisEventStore(redisTemplate: ReactiveStringRedisTemplate): EventStore {
        return RedisEventStore(redisTemplate)
    }

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnProperty(
        SnapshotProperties.STORAGE,
        havingValue = SnapshotStorage.REDIS_NAME,
    )
    fun redisSnapshotRepository(redisTemplate: ReactiveStringRedisTemplate): SnapshotRepository {
        return RedisSnapshotRepository(redisTemplate)
    }
}
