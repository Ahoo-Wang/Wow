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

package me.ahoo.wow.tck.container

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.testcontainers.containers.GenericContainer

class RedisTestFixture(
    private val keyPrefix: String = "wow_it",
) : BeforeEachCallback, AfterEachCallback, TestWatcher {
    private var redisContainer: GenericContainer<*>? = null

    lateinit var connectionFactory: LettuceConnectionFactory
        private set

    lateinit var redisTemplate: ReactiveStringRedisTemplate
        private set

    lateinit var prefix: String
        private set

    override fun beforeEach(context: ExtensionContext) {
        val redisContainer = redis()
        prefix = ContainerTestIds.nextName(keyPrefix)
        val lettuceClientConfiguration = LettuceClientConfiguration
            .builder()
            .build()
        val redisConfig = RedisStandaloneConfiguration(
            redisContainer.host,
            redisContainer.getMappedPort(6379),
        )
        connectionFactory = LettuceConnectionFactory(redisConfig, lettuceClientConfiguration)
        connectionFactory.afterPropertiesSet()
        redisTemplate = ReactiveStringRedisTemplate(connectionFactory)
    }

    fun key(name: String): String = "$prefix:$name"

    private fun redis(): GenericContainer<*> {
        return redisContainer ?: WowTestContainers.redis
            .also {
                redisContainer = it
            }
    }

    override fun afterEach(context: ExtensionContext) {
        if (::connectionFactory.isInitialized) {
            connectionFactory.destroy()
        }
    }

    override fun testFailed(context: ExtensionContext, cause: Throwable?) {
        ContainerDiagnostics.printFailure("redis", redisContainer, cause)
    }
}
