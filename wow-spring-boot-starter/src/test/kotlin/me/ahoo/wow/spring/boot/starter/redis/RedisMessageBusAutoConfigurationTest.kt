package me.ahoo.wow.spring.boot.starter.redis

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.redis.bus.RedisCommandBus
import me.ahoo.wow.redis.bus.RedisDomainEventBus
import me.ahoo.wow.redis.bus.RedisStateEventBus
import me.ahoo.wow.spring.boot.starter.BusType
import me.ahoo.wow.spring.boot.starter.command.CommandProperties
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.event.EventProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.state.StateProperties
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

class RedisMessageBusAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${CommandProperties.BUS_TYPE}=${BusType.REDIS_NAME}",
                "${EventProperties.BUS_TYPE}=${BusType.REDIS_NAME}",
                "${StateProperties.BUS_TYPE}=${BusType.REDIS_NAME}",
            )
            .withBean(ReactiveStringRedisTemplate::class.java, {
                mockk<ReactiveStringRedisTemplate> {
                    every { opsForStream<String, String>() } returns mockk()
                }
            })
            .withUserConfiguration(
                RedisMessageBusAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(RedisCommandBus::class.java)
                    .hasSingleBean(RedisDomainEventBus::class.java)
                    .hasSingleBean(RedisStateEventBus::class.java)
            }
    }
}
