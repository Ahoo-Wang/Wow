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

import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.redis.bus.RedisCommandBus
import me.ahoo.wow.redis.bus.RedisDomainEventBus
import me.ahoo.wow.redis.bus.RedisStateEventBus
import me.ahoo.wow.spring.boot.starter.BusType
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.command.CommandProperties
import me.ahoo.wow.spring.boot.starter.event.EventProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.state.StateProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

@AutoConfiguration(
    before = [CommandAutoConfiguration::class],
    after = [DataRedisReactiveAutoConfiguration::class],
)
@ConditionalOnWowEnabled
@ConditionalOnRedisEnabled
@ConditionalOnClass(RedisCommandBus::class)
class RedisMessageBusAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
        CommandProperties.BUS_TYPE,
        havingValue = BusType.REDIS_NAME,
    )
    fun redisCommandBus(redisTemplate: ReactiveStringRedisTemplate): DistributedCommandBus {
        return RedisCommandBus(redisTemplate)
    }

    @Bean
    @ConditionalOnProperty(
        EventProperties.BUS_TYPE,
        havingValue = BusType.REDIS_NAME,
    )
    fun redisDomainEventBus(redisTemplate: ReactiveStringRedisTemplate): DistributedDomainEventBus {
        return RedisDomainEventBus(redisTemplate)
    }

    @Bean
    @ConditionalOnProperty(
        StateProperties.BUS_TYPE,
        havingValue = BusType.REDIS_NAME,
    )
    fun redisStateEventBus(redisTemplate: ReactiveStringRedisTemplate): DistributedStateEventBus {
        return RedisStateEventBus(redisTemplate)
    }
}
