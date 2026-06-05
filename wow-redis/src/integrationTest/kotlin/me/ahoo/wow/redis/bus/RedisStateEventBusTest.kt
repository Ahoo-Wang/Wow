package me.ahoo.wow.redis.bus

import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.tck.container.RedisTestFixture
import me.ahoo.wow.tck.eventsourcing.state.StateEventBusSpec
import org.junit.jupiter.api.extension.RegisterExtension

class RedisStateEventBusTest : StateEventBusSpec() {
    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    override fun createMessageBus(): StateEventBus {
        return RedisStateEventBus(redis.redisTemplate)
    }
}
