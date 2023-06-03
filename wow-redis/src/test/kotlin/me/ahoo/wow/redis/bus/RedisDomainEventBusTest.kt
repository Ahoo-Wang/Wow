package me.ahoo.wow.redis.bus

import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.redis.RedisInitializer
import me.ahoo.wow.tck.event.DomainEventBusSpec
import org.junit.jupiter.api.BeforeEach

class RedisDomainEventBusTest : DomainEventBusSpec() {
    protected lateinit var redisInitializer: RedisInitializer

    @BeforeEach
    fun setup() {
        redisInitializer = RedisInitializer()
    }

    override fun createMessageBus(): DomainEventBus {
        return RedisDomainEventBus(redisInitializer.redisTemplate)
    }
}