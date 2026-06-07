/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.infrastructure.redis

import me.ahoo.wow.infrastructure.InfrastructureAvailability
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.ReactiveRedisConnection
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.Duration

class RedisBenchmarkFixture : AutoCloseable {
    companion object {
        private val FLUSH_TIMEOUT: Duration = Duration.ofSeconds(30)
        private const val DEFAULT_BENCHMARK_DATABASE = 15
        private const val REDIS_DATABASE_PROPERTY = "wow.benchmark.redis.database"

        private fun benchmarkDatabase(): Int {
            val configuredDatabase = System.getProperty(REDIS_DATABASE_PROPERTY)
            val database = configuredDatabase?.toIntOrNull() ?: DEFAULT_BENCHMARK_DATABASE
            require(configuredDatabase == null || configuredDatabase.toIntOrNull() != null) {
                "$REDIS_DATABASE_PROPERTY must be an integer."
            }
            require(database >= 0) {
                "$REDIS_DATABASE_PROPERTY must be greater than or equal to 0."
            }
            return database
        }
    }

    val connectionFactory: LettuceConnectionFactory
    val redisTemplate: ReactiveStringRedisTemplate

    init {
        InfrastructureAvailability.requireRedis()
        val lettuceClientConfiguration = LettuceClientConfiguration
            .builder()
            .build()
        val redisConfig = RedisStandaloneConfiguration().apply {
            database = benchmarkDatabase()
        }
        connectionFactory = LettuceConnectionFactory(redisConfig, lettuceClientConfiguration)
        connectionFactory.afterPropertiesSet()
        redisTemplate = ReactiveStringRedisTemplate(connectionFactory)
        flushDb()
    }

    override fun close() {
        runCatching {
            flushDb()
        }
        connectionFactory.destroy()
    }

    private fun flushDb() {
        val connection: ReactiveRedisConnection = connectionFactory.reactiveConnection
        try {
            connection.serverCommands().flushDb().block(FLUSH_TIMEOUT)
        } finally {
            connection.close()
        }
    }
}
