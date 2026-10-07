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

package me.ahoo.wow.redis.bus

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.connection.ReactiveRedisConnection
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.ReactiveStreamCommands
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class RedisStreamReadConnectionFactoryTest {
    private val streamCommands = mockk<ReactiveStreamCommands>()
    private val connection = mockk<ReactiveRedisConnection> {
        every { streamCommands() } returns streamCommands
        every { closeLater() } returns Mono.empty()
    }
    private val delegate = mockk<ReactiveRedisConnectionFactory> {
        every { reactiveConnection } returns connection
    }

    @Test
    fun `every read gets the one connection, and closing it after a read keeps it open`() {
        val factory = RedisStreamReadConnectionFactory(delegate)

        repeat(3) {
            val read = factory.reactiveConnection
            read.streamCommands().assert().isSameAs(streamCommands)
            read.closeLater().test().verifyComplete()
            read.close()
        }

        verify(exactly = 1) { delegate.reactiveConnection }
        verify(exactly = 0) { connection.closeLater() }
    }

    @Test
    fun `closing the factory closes the connection and refuses later reads`() {
        val factory = RedisStreamReadConnectionFactory(delegate)
        factory.reactiveConnection

        factory.closeLater().test().verifyComplete()

        verify(exactly = 1) { connection.closeLater() }
        assertThrownBy<IllegalStateException> { factory.reactiveConnection }
        // Closed once: the connection is not closed again.
        factory.closeLater().test().verifyComplete()
        verify(exactly = 1) { connection.closeLater() }
    }

    @Test
    fun `a stream closed before its first read opens no connection`() {
        val factory = RedisStreamReadConnectionFactory(delegate)

        factory.closeLater().test().verifyComplete()

        verify(exactly = 0) { delegate.reactiveConnection }
    }

    @Test
    fun `a stream holds its read connection when the factory has no pool`() {
        val unpooled = LettuceConnectionFactory(
            RedisStandaloneConfiguration(),
            LettuceClientConfiguration.defaultConfiguration(),
        )

        RedisStreamReadConnectionFactory.holdsReadConnection(unpooled).assert().isTrue()
        // A wrapped or proxied factory is not recognised as pooled.
        RedisStreamReadConnectionFactory.holdsReadConnection(delegate).assert().isTrue()
    }

    @Test
    fun `a stream borrows per read when the Lettuce factory has a pool`() {
        // LettucePoolingClientConfiguration needs commons-pool2, a test-only dependency of wow-redis.
        val pooled = LettuceConnectionFactory(
            RedisStandaloneConfiguration(),
            LettucePoolingClientConfiguration.defaultConfiguration(),
        )

        RedisStreamReadConnectionFactory.holdsReadConnection(pooled).assert().isFalse()
    }

    @Test
    fun `exceptions are translated by the delegate and cluster connections are not handed out`() {
        val translated = DataAccessResourceFailureException("translated")
        val failure = IllegalStateException("failure")
        every { delegate.translateExceptionIfPossible(failure) } returns translated
        val factory = RedisStreamReadConnectionFactory(delegate)

        factory.translateExceptionIfPossible(failure).assert().isSameAs(translated)
        assertThrownBy<UnsupportedOperationException> { factory.reactiveClusterConnection }
    }
}
