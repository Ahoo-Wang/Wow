package me.ahoo.wow.redis

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.tck.eventsourcing.EventStoreSpec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

class RedisEventStoreTest : EventStoreSpec() {
    protected lateinit var connectionFactory: LettuceConnectionFactory
    protected lateinit var redisTemplate: ReactiveStringRedisTemplate

    @BeforeEach
    override fun setup() {
        val lettuceClientConfiguration = LettuceClientConfiguration
            .builder()
            .build()
        val redisConfig = RedisStandaloneConfiguration()
        connectionFactory = LettuceConnectionFactory(redisConfig, lettuceClientConfiguration)
        connectionFactory.afterPropertiesSet()
        redisTemplate = ReactiveStringRedisTemplate(connectionFactory)
        super.setup()
    }

    @AfterEach
    fun destroy() {
        connectionFactory.destroy()
    }

    override fun createEventStore(): EventStore {
        return RedisEventStore(redisTemplate)
    }
}