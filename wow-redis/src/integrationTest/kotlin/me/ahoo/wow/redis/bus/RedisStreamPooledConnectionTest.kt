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
 * the factory for a connection per read and gives it back after the read: it keeps no pool slot between reads.
 *
 * Spring's pooling provider keeps two pools of `max-active` each: a blocking one, from which the template's shared
 * connection comes, and an asynchronous one, from which every blocking `XREADGROUP … BLOCK` read borrows. The
 * asynchronous pool does not wait for a free connection: a read that finds none fails at once with "Pool exhausted"
 * (as before 9.3). A read holds its connection for as long as it blocks, so the asynchronous pool needs one connection
 * per receive stream. With exactly that many, CI still saw an occasional exhaustion, most likely because a read's
 * connection goes back asynchronously and the next read can ask before it is back; hence the documented minimum of
 * streams + 1, an empirical margin.
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
        val factory = pooledFactory(maxActive = 2)

        RedisStreamReadConnectionFactory.holdsReadConnection(factory).assert().isFalse()
    }

    @Test
    fun `a pooled receive stream borrows a connection per read, and one pooled connection serves it`() {
        // max-active 1: the template's shared connection comes from the other (blocking) pool, so it does not take
        // the slot the stream's reads borrow.
        val factory = spyk(pooledFactory(maxActive = 1))
        val bus = pooledBus(factory, pollTimeout = Duration.ofMillis(100))
        val errors = CopyOnWriteArrayList<Throwable>()
        val received = startReceiving(bus, errors)
        roundTrip(bus, received)
        clearMocks(factory, answers = false, recordedCalls = true)

        // About 10 empty polls; a stream holding one connection would ask the factory once.
        Thread.sleep(1_000)

        verify(atLeast = 5) { factory.reactiveConnection }
        roundTrip(bus, received)
        errors.assert().isEmpty()
    }

    @Test
    fun `receive streams plus one pooled connections serve them all`() {
        val factory = pooledFactory(maxActive = 3)
        val bus = pooledBus(factory, pollTimeout = Duration.ofMillis(100))
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
        // 3 idle streams, 2 connections: two reads block in XREADGROUP for the whole poll timeout, each holding one,
        // and the third read finds the pool exhausted.
        val factory = pooledFactory(maxActive = 2)
        val bus = pooledBus(factory, pollTimeout = Duration.ofSeconds(2))
        val errors = CopyOnWriteArrayList<Throwable>()
        repeat(3) { startReceiving(bus, errors) }

        val deadline = System.nanoTime() + TIMEOUT.toNanos()
        while (errors.isEmpty()) {
            check(System.nanoTime() < deadline) { "No receive stream failed to borrow a pooled connection." }
            Thread.sleep(20)
        }
        errors.first().causes().any { it is NoSuchElementException }.assert().isTrue()
    }

    private fun pooledFactory(maxActive: Int): LettuceConnectionFactory {
        val poolConfig = GenericObjectPoolConfig<StatefulConnection<*, *>>().apply {
            maxTotal = maxActive
            maxIdle = maxActive
        }
        val factory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379)),
            LettucePoolingClientConfiguration.builder().poolConfig(poolConfig).build(),
        )
        factory.afterPropertiesSet()
        connectionFactories += factory
        return factory
    }

    private fun pooledBus(factory: LettuceConnectionFactory, pollTimeout: Duration) = RedisCommandBus(
        redisTemplate = ReactiveStringRedisTemplate(factory),
        pollTimeout = pollTimeout,
        failurePolicy = TransportFailurePolicy(TransportFailurePolicy.receiveRetry(maxAttempts = 0)),
    )

    private fun roundTrip(bus: RedisCommandBus, received: Sinks.Many<String>) {
        val message = createMessage()
        val acknowledged = received.asFlux().filter { it == message.id }.next().toFuture()
        bus.send(message).block(TIMEOUT)
        acknowledged.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
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
