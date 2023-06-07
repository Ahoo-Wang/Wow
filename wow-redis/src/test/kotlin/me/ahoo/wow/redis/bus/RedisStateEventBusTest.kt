package me.ahoo.wow.redis.bus

import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.redis.RedisInitializer
import me.ahoo.wow.tck.eventsourcing.state.StateEventBusSpec
import org.junit.jupiter.api.BeforeEach

class RedisStateEventBusTest : StateEventBusSpec() {
    protected lateinit var redisInitializer: RedisInitializer

    @BeforeEach
    fun setup() {
        redisInitializer = RedisInitializer()
    }

    override fun createMessageBus(): StateEventBus {
        return RedisStateEventBus(redisInitializer.redisTemplate)
    }
}
