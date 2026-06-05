package me.ahoo.wow.redis.bus

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.tck.container.RedisTestFixture
import me.ahoo.wow.tck.command.CommandBusSpec
import org.junit.jupiter.api.extension.RegisterExtension

class RedisCommandBusTest : CommandBusSpec() {
    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    override fun createMessageBus(): CommandBus {
        return RedisCommandBus(redis.redisTemplate)
    }
}
