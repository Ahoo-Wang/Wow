package me.ahoo.wow.redis.prepare

import me.ahoo.wow.infra.prepare.PrepareKeyFactory
import me.ahoo.wow.tck.container.RedisTestFixture
import me.ahoo.wow.tck.prepare.PrepareKeySpec
import org.junit.jupiter.api.extension.RegisterExtension

abstract class RedisPrepareKeySpec<V : Any> : PrepareKeySpec<V>() {
    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    protected val prepareKeyFactory: PrepareKeyFactory
        get() = RedisPrepareKeyFactory(redis.redisTemplate)
}
