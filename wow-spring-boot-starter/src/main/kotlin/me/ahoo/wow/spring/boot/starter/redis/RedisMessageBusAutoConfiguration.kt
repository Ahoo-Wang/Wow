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
import me.ahoo.wow.redis.bus.CompositeRedisMessageBusObserver
import me.ahoo.wow.redis.bus.RedisCommandBus
import me.ahoo.wow.redis.bus.RedisDomainEventBus
import me.ahoo.wow.redis.bus.RedisMessageBusObserver
import me.ahoo.wow.redis.bus.RedisStateEventBus
import me.ahoo.wow.spring.boot.starter.BusType
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.command.CommandProperties
import me.ahoo.wow.spring.boot.starter.event.EventAutoConfiguration
import me.ahoo.wow.spring.boot.starter.event.EventProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.state.StateAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.state.StateProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

@AutoConfiguration(
    before = [
        CommandAutoConfiguration::class,
        EventAutoConfiguration::class,
        StateAutoConfiguration::class,
    ],
    after = [DataRedisReactiveAutoConfiguration::class],
)
@ConditionalOnWowEnabled
@ConditionalOnRedisEnabled
@ConditionalOnClass(RedisCommandBus::class)
@EnableConfigurationProperties(RedisStreamRecoveryProperties::class)
class RedisMessageBusAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
        CommandProperties.BUS_TYPE,
        havingValue = BusType.REDIS_NAME,
    )
    @ConditionalOnMissingBean(DistributedCommandBus::class)
    fun redisCommandBus(
        redisTemplate: ReactiveStringRedisTemplate,
        recoveryProperties: RedisStreamRecoveryProperties,
        observers: ObjectProvider<RedisMessageBusObserver>,
    ): DistributedCommandBus {
        return RedisCommandBus(
            redisTemplate = redisTemplate,
            recoveryOptions = recoveryProperties.toOptions(),
            observer = observers.toObserver(),
        )
    }

    @Bean
    @ConditionalOnProperty(
        EventProperties.BUS_TYPE,
        havingValue = BusType.REDIS_NAME,
    )
    @ConditionalOnMissingBean(DistributedDomainEventBus::class)
    fun redisDomainEventBus(
        redisTemplate: ReactiveStringRedisTemplate,
        recoveryProperties: RedisStreamRecoveryProperties,
        observers: ObjectProvider<RedisMessageBusObserver>,
    ): DistributedDomainEventBus {
        return RedisDomainEventBus(
            redisTemplate = redisTemplate,
            recoveryOptions = recoveryProperties.toOptions(),
            observer = observers.toObserver(),
        )
    }

    @Bean
    @ConditionalOnProperty(
        StateProperties.BUS_TYPE,
        havingValue = BusType.REDIS_NAME,
    )
    @ConditionalOnMissingBean(DistributedStateEventBus::class)
    fun redisStateEventBus(
        redisTemplate: ReactiveStringRedisTemplate,
        recoveryProperties: RedisStreamRecoveryProperties,
        observers: ObjectProvider<RedisMessageBusObserver>,
    ): DistributedStateEventBus {
        return RedisStateEventBus(
            redisTemplate = redisTemplate,
            recoveryOptions = recoveryProperties.toOptions(),
            observer = observers.toObserver(),
        )
    }

    private fun ObjectProvider<RedisMessageBusObserver>.toObserver(): RedisMessageBusObserver {
        val observers = orderedStream().toList()
        return when (observers.size) {
            0 -> RedisMessageBusObserver.NOOP
            1 -> observers.single()
            else -> CompositeRedisMessageBusObserver(observers)
        }
    }
}
