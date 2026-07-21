package me.ahoo.wow.spring.boot.starter.redis

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.LocalFirstDomainEventBus
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.LocalFirstStateEventBus
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.redis.bus.AbstractRedisMessageBus
import me.ahoo.wow.redis.bus.RedisCommandBus
import me.ahoo.wow.redis.bus.RedisDomainEventBus
import me.ahoo.wow.redis.bus.RedisMessageBusObservation
import me.ahoo.wow.redis.bus.RedisMessageBusObserver
import me.ahoo.wow.redis.bus.RedisStateEventBus
import me.ahoo.wow.redis.bus.RedisStreamRecoveryOptions
import me.ahoo.wow.spring.boot.starter.BusType
import me.ahoo.wow.spring.boot.starter.command.CommandProperties
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.event.EventAutoConfiguration
import me.ahoo.wow.spring.boot.starter.event.EventProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.state.StateAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.state.StateProperties
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.Duration

class RedisMessageBusAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should expose recovery property defaults without Spring binding`() {
        RedisStreamRecoveryProperties().toOptions()
            .assert()
            .isEqualTo(RedisStreamRecoveryOptions.DEFAULT)
    }

    @Test
    fun `should load context with redis message bus beans`() {
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
                context.assert()
                    .hasSingleBean(RedisCommandBus::class.java)
                    .hasSingleBean(RedisDomainEventBus::class.java)
                    .hasSingleBean(RedisStateEventBus::class.java)
                    .hasSingleBean(RedisStreamRecoveryProperties::class.java)
                context.getBean(RedisStreamRecoveryProperties::class.java)
                    .enabled
                    .assert()
                    .isTrue()
            }
    }

    @Test
    fun `should configure local-first buses after redis distributed buses`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${EventProperties.BUS_TYPE}=${BusType.REDIS_NAME}",
                "${StateProperties.BUS_TYPE}=${BusType.REDIS_NAME}",
            )
            .withBean(EventStore::class.java, { mockk<EventStore>() })
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(ReactiveStringRedisTemplate::class.java, {
                mockk<ReactiveStringRedisTemplate> {
                    every { opsForStream<String, String>() } returns mockk()
                }
            })
            .withConfiguration(
                AutoConfigurations.of(
                    EventAutoConfiguration::class.java,
                    StateAutoConfiguration::class.java,
                    RedisMessageBusAutoConfiguration::class.java,
                )
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(RedisDomainEventBus::class.java)
                    .hasSingleBean(RedisStateEventBus::class.java)
                    .hasSingleBean(LocalFirstDomainEventBus::class.java)
                    .hasSingleBean(LocalFirstStateEventBus::class.java)
            }
    }

    @Test
    fun `should back off when custom distributed buses exist`() {
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
            .withBean(DistributedCommandBus::class.java, { mockk<DistributedCommandBus>() })
            .withBean(DistributedDomainEventBus::class.java, { mockk<DistributedDomainEventBus>() })
            .withBean(DistributedStateEventBus::class.java, { mockk<DistributedStateEventBus>() })
            .withUserConfiguration(RedisMessageBusAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(DistributedCommandBus::class.java)
                    .hasSingleBean(DistributedDomainEventBus::class.java)
                    .hasSingleBean(DistributedStateEventBus::class.java)
                    .doesNotHaveBean(RedisCommandBus::class.java)
                    .doesNotHaveBean(RedisDomainEventBus::class.java)
                    .doesNotHaveBean(RedisStateEventBus::class.java)
            }
    }

    @Test
    fun `should bind redis pending-message recovery options`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${CommandProperties.BUS_TYPE}=${BusType.REDIS_NAME}",
                "${RedisStreamRecoveryProperties.PREFIX}.enabled=false",
                "${RedisStreamRecoveryProperties.PREFIX}.min-idle-time=2m",
                "${RedisStreamRecoveryProperties.PREFIX}.interval=3s",
                "${RedisStreamRecoveryProperties.PREFIX}.batch-size=25",
            )
            .withBean(ReactiveStringRedisTemplate::class.java, {
                mockk<ReactiveStringRedisTemplate> {
                    every { opsForStream<String, String>() } returns mockk()
                }
            })
            .withUserConfiguration(RedisMessageBusAutoConfiguration::class.java)
            .run { context ->
                context.assert().hasNotFailed().hasSingleBean(RedisCommandBus::class.java)
                val recovery = context.getBean(RedisStreamRecoveryProperties::class.java)
                recovery.enabled.assert().isFalse()
                recovery.minIdleTime.assert().isEqualTo(Duration.ofMinutes(2))
                recovery.interval.assert().isEqualTo(Duration.ofSeconds(3))
                recovery.batchSize.assert().isEqualTo(25)
                recovery.toOptions().enabled.assert().isFalse()
            }
    }

    @Test
    fun `should compose every redis message-bus observer`() {
        val failingObserver = mockk<RedisMessageBusObserver>()
        every { failingObserver.onObservation(any()) } throws IllegalStateException("observer failed")
        val recordingObserver = mockk<RedisMessageBusObserver>(relaxed = true)
        contextRunner
            .enableWow()
            .withPropertyValues("${CommandProperties.BUS_TYPE}=${BusType.REDIS_NAME}")
            .withBean(ReactiveStringRedisTemplate::class.java, {
                mockk<ReactiveStringRedisTemplate> {
                    every { opsForStream<String, String>() } returns mockk()
                }
            })
            .withBean("failingRedisObserver", RedisMessageBusObserver::class.java, { failingObserver })
            .withBean("recordingRedisObserver", RedisMessageBusObserver::class.java, { recordingObserver })
            .withUserConfiguration(RedisMessageBusAutoConfiguration::class.java)
            .run { context ->
                context.assert().hasNotFailed().hasSingleBean(RedisCommandBus::class.java)
                val observation = RedisMessageBusObservation.PendingScanFailed(
                    topic = "topic",
                    consumerGroup = "group",
                    failureType = IllegalStateException::class.java.name,
                )
                val observerField = AbstractRedisMessageBus::class.java
                    .getDeclaredField("messageBusObserver")
                    .apply { isAccessible = true }
                val observer = observerField.get(context.getBean(RedisCommandBus::class.java))
                    as RedisMessageBusObserver

                observer.onObservation(observation)

                verify(exactly = 1) { failingObserver.onObservation(observation) }
                verify(exactly = 1) { recordingObserver.onObservation(observation) }
            }
    }

    @Test
    fun `should reuse a single redis message-bus observer`() {
        val singleObserver = mockk<RedisMessageBusObserver>()
        contextRunner
            .enableWow()
            .withPropertyValues("${CommandProperties.BUS_TYPE}=${BusType.REDIS_NAME}")
            .withBean(ReactiveStringRedisTemplate::class.java, {
                mockk<ReactiveStringRedisTemplate> {
                    every { opsForStream<String, String>() } returns mockk()
                }
            })
            .withBean(RedisMessageBusObserver::class.java, { singleObserver })
            .withUserConfiguration(RedisMessageBusAutoConfiguration::class.java)
            .run { context ->
                context.assert().hasNotFailed()
                val observerField = AbstractRedisMessageBus::class.java
                    .getDeclaredField("messageBusObserver")
                    .apply { isAccessible = true }

                observerField.get(context.getBean(RedisCommandBus::class.java))
                    .assert()
                    .isSameAs(singleObserver)
            }
    }
}
