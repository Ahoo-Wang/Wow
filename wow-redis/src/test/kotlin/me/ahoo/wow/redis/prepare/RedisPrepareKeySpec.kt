package me.ahoo.wow.redis.prepare

import me.ahoo.wow.infra.prepare.PrepareKeyFactory
import me.ahoo.wow.redis.RedisInitializer
import me.ahoo.wow.tck.prepare.PrepareKeySpec
import org.junit.jupiter.api.BeforeEach

abstract class RedisPrepareKeySpec<V : Any> : PrepareKeySpec<V>() {
    protected lateinit var redisInitializer: RedisInitializer

    @BeforeEach
    override fun setup() {
        redisInitializer = RedisInitializer()
        super.setup()
    }
    protected val prepareKeyFactory: PrepareKeyFactory
        get() = RedisPrepareKeyFactory(redisInitializer.redisTemplate)
}
