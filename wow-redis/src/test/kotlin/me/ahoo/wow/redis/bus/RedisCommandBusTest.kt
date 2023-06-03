package me.ahoo.wow.redis.bus

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.redis.RedisInitializer
import me.ahoo.wow.tck.command.CommandBusSpec
import org.junit.jupiter.api.BeforeEach

class RedisCommandBusTest : CommandBusSpec() {
    protected lateinit var redisInitializer: RedisInitializer

    @BeforeEach
    fun setup() {
        redisInitializer = RedisInitializer()
    }

    override fun createMessageBus(): CommandBus {
        return RedisCommandBus(redisInitializer.redisTemplate)
    }
}
