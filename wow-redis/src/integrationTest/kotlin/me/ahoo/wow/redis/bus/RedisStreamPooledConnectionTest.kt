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

import io.mockk.clearMocks
import io.mockk.spyk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.transport.TransportFailurePolicy
import me.ahoo.wow.tck.container.ContainerImages
import me.ahoo.wow.tck.mock.MockCreateAggregate
import io.lettuce.core.api.StatefulConnection
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import reactor.core.Disposable
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.NoSuchElementException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * With a pooled Lettuce factory (`LettucePoolingClientConfiguration`, which needs commons-pool2) a receive stream asks
 * the factory for a connection per read and gives it back after the read: it keeps no pool slot between reads. While
 * its blocking `XREADGROUP … BLOCK` waits, though, it holds one, and Lettuce's reactive pool does not wait for a free
 * one: with fewer connections than receive streams reading at once, a read fails with "Pool exhausted" (as before
 * 9.3). Hence `max-active` ≥ receive streams + 1, the one for the application's other dedicated connections.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisStreamPooledConnectionTest {
    private val redis = GenericContainer(DockerImageName.parse(ContainerImages.REDIS)).withExposedPorts(6379)
    private val connectionFactories = CopyOnWriteArrayList<LettuceConnectionFactory>()
    private val receiving = CopyOnWriteArrayList<Disposable>()

    @BeforeAll
    fun startRedis() {
        redis.start()
    }

    @AfterAll
    fun stopRedis() {
        redis.stop()
    }

    @AfterEach
    fun disconnect() {
        receiving.forEach(Disposable::dispose)
        receiving.clear()
        connectionFactories.forEach(LettuceConnectionFactory::destroy)
        connectionFactories.clear()
    }

    @Test
    fun `a pooled factory is not given a held read connection`() {
        val factory = pooledFactory(maxActive = 2, maxWait = Duration.ofSeconds(1))

        RedisStreamReadConnectionFactory.holdsReadConnection(factory).assert().isFalse()
    }

    @Test
    fun `a pooled receive stream borrows a connection per read`() {
        val factory = spyk(pooledFactory(maxActive = 2, maxWait = Duration.ofSeconds(1)))
        val bus = RedisCommandBus(
            redisTemplate = ReactiveStringRedisTemplate(factory),
            pollTimeout = Duration.ofMillis(100),
            failurePolicy = TransportFailurePolicy(TransportFailurePolicy.receiveRetry(maxAttempts = 0)),
        )
        val errors = CopyOnWriteArrayList<Throwable>()
        startReceiving(bus, errors)
        clearMocks(factory, answers = false, recordedCalls = true)

        // About 10 empty polls; a stream holding one connection would ask the factory once.
        Thread.sleep(1_000)

        verify(atLeast = 5) { factory.reactiveConnection }
        errors.assert().isEmpty()
    }

    @Test
    fun `as many pooled connections as receive streams serve them all`() {
        val factory = pooledFactory(maxActive = 2, maxWait = Duration.ofSeconds(1))
        val bus = RedisCommandBus(
            redisTemplate = ReactiveStringRedisTemplate(factory),
            pollTimeout = Duration.ofMillis(100),
            failurePolicy = TransportFailurePolicy(TransportFailurePolicy.receiveRetry(maxAttempts = 0)),
        )
        val errors = CopyOnWriteArrayList<Throwable>()
        val first = startReceiving(bus, errors)
        val second = startReceiving(bus, errors)

        repeat(3) {
            val message = createMessage()
            val receivedByFirst = first.asFlux().filter { it == message.id }.next().toFuture()
            val receivedBySecond = second.asFlux().filter { it == message.id }.next().toFuture()
            bus.send(message).block(TIMEOUT)
            receivedByFirst.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            receivedBySecond.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        }
        errors.assert().isEmpty()
    }

    @Test
    fun `fewer pooled connections than receive streams exhaust the pool`() {
        // An idle stream blocks in XREADGROUP for its whole poll timeout holding the only connection; the other
        // stream's read finds the pool exhausted.
        val factory = pooledFactory(maxActive = 1, maxWait = Duration.ofMillis(100))
        val bus = RedisCommandBus(
            redisTemplate = ReactiveStringRedisTemplate(factory),
            pollTimeout = Duration.ofSeconds(2),
            failurePolicy = TransportFailurePolicy(TransportFailurePolicy.receiveRetry(maxAttempts = 0)),
        )
        val errors = CopyOnWriteArrayList<Throwable>()
        startReceiving(bus, errors)
        startReceiving(bus, errors)

        val deadline = System.nanoTime() + TIMEOUT.toNanos()
        while (errors.isEmpty()) {
            check(System.nanoTime() < deadline) { "No receive stream failed to borrow a pooled connection." }
            Thread.sleep(20)
        }
        errors.first().causes().any { it is NoSuchElementException }.assert().isTrue()
    }

    private fun pooledFactory(maxActive: Int, maxWait: Duration): LettuceConnectionFactory {
        val poolConfig = GenericObjectPoolConfig<StatefulConnection<*, *>>().apply {
            maxTotal = maxActive
            maxIdle = maxActive
            setMaxWait(maxWait)
        }
        val factory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379)),
            LettucePoolingClientConfiguration.builder().poolConfig(poolConfig).build(),
        )
        factory.afterPropertiesSet()
        connectionFactories += factory
        return factory
    }

    private fun startReceiving(bus: RedisCommandBus, errors: MutableList<Throwable>): Sinks.Many<String> {
        val received = Sinks.many().multicast().directBestEffort<String>()
        val receiver = bus.receiver(MessageSubscription(requiredNamedAggregate<MockCreateAggregate>(), generateGlobalId()))
        receiving += receiver.openedMessages()
            .concatMap { exchange: ServerCommandExchange<*> ->
                exchange.acknowledge().doFinally { received.tryEmitNext(exchange.message.id) }
            }
            .subscribe({}, { errors += it })
        receiver.readiness.block(TIMEOUT)
        return received
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private fun createMessage() = MockCreateAggregate(
        id = GlobalIdGenerator.generateAsString(),
        data = GlobalIdGenerator.generateAsString(),
    ).toCommandMessage()

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
