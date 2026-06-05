package me.ahoo.wow.redis.bus

import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.tck.container.RedisTestFixture
import me.ahoo.wow.tck.event.DomainEventBusSpec
import org.junit.jupiter.api.extension.RegisterExtension

class RedisDomainEventBusTest : DomainEventBusSpec() {
    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    override fun createMessageBus(): DomainEventBus {
        return RedisDomainEventBus(redis.redisTemplate)
    }
}
