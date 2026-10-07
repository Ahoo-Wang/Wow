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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.transport.TransportFailurePolicy
import me.ahoo.wow.tck.container.ContainerImages
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.connection.ReactiveRedisConnection
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * A receiver reads with blocking `XREADGROUP … BLOCK`, which Lettuce cannot multiplex on the shared connection. With
 * the connection factory Spring Boot configures by default (no pool), each such read must not open a TCP connection
 * of its own: under load that is one connection per batch, and the closed ones pile up in `TIME_WAIT` until the
 * client runs out of ephemeral ports. The container is this test's own, so Redis' connection counter sees only it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisStreamConnectionReuseTest {
    private val redis = GenericContainer(DockerImageName.parse(ContainerImages.REDIS)).withExposedPorts(6379)
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: ReactiveStringRedisTemplate
    private var receiving: Disposable? = null

    @BeforeAll
    fun startRedis() {
        redis.start()
    }

    @AfterAll
    fun stopRedis() {
        redis.stop()
    }

    @BeforeEach
    fun connect() {
        connectionFactory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379)),
            LettuceClientConfiguration.builder().build(),
        )
        connectionFactory.afterPropertiesSet()
        redisTemplate = ReactiveStringRedisTemplate(connectionFactory)
    }

    @AfterEach
    fun disconnect() {
        receiving?.dispose()
        connectionFactory.destroy()
    }

    @Test
    fun `round trips through one receiver open a bounded number of connections`() {
        val bus = RedisCommandBus(redisTemplate = redisTemplate)
        val received = startReceiving(bus)
        val before = connectionsReceived()

        repeat(ROUND_TRIPS) {
            val message = createMessage()
            val acknowledged = received.asFlux().filter { it == message.id }.next().toFuture()
            bus.send(message).block(TIMEOUT)
            acknowledged.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        }

        val opened = connectionsReceived() - before
        println("Redis connections opened over $ROUND_TRIPS round trips: $opened")
        opened.assert().isLessThanOrEqualTo(MAX_NEW_CONNECTIONS)
    }

    @Test
    fun `an idle receiver polls without opening a connection per poll`() {
        val bus = RedisCommandBus(redisTemplate = redisTemplate, pollTimeout = Duration.ofMillis(100))
        startReceiving(bus)
        val before = connectionsReceived()

        // About 20 empty polls.
        Mono.delay(Duration.ofSeconds(2)).block()

        val opened = connectionsReceived() - before
        println("Redis connections opened over 2 s of idle polling: $opened")
        opened.assert().isLessThanOrEqualTo(MAX_NEW_CONNECTIONS)
    }

    @Test
    fun `the held connection is closed when receiving stops`() {
        val bus = RedisCommandBus(redisTemplate = redisTemplate, pollTimeout = Duration.ofMillis(100))
        val baseline = connectedClients()
        val received = startReceiving(bus)
        roundTrip(bus, received)
        connectedClients().assert().isGreaterThan(baseline)

        receiving!!.dispose()

        awaitConnectedClients(baseline)
    }

    @Test
    fun `a receive stream that fails closes its connection before it is retried`() {
        val bus = RedisCommandBus(
            redisTemplate = redisTemplate,
            pollTimeout = Duration.ofMillis(100),
            failurePolicy = TransportFailurePolicy(TransportFailurePolicy.receiveRetry(minBackoff = Duration.ofMillis(100))),
        )
        val baseline = connectedClients()
        val group = generateGlobalId()
        val received = startReceiving(bus, group)
        roundTrip(bus, received)
        val receivingClients = connectedClients()
        val topic = DefaultCommandTopicConverter.convert(requiredNamedAggregate<MockCreateAggregate>())

        // XREADGROUP fails with NOGROUP; the retried stream creates the group again and reads on a new connection.
        val streamOps = redisTemplate.opsForStream<String, String>()
        streamOps.destroyGroup(topic, group).block(TIMEOUT)
        // The group is created again at the stream's end, so send only once it is back.
        val deadline = System.nanoTime() + TIMEOUT.toNanos()
        while (streamOps.groups(topic).map { it.groupName() }.collectList().block(TIMEOUT)!!.none { it == group }) {
            check(System.nanoTime() < deadline) { "The retried stream did not create its group again." }
            Mono.delay(Duration.ofMillis(50)).block()
        }
        roundTrip(bus, received)

        awaitConnectedClients(receivingClients)
        receiving!!.dispose()
        awaitConnectedClients(baseline)
    }

    private fun roundTrip(bus: RedisCommandBus, received: Sinks.Many<String>) {
        val message = createMessage()
        val acknowledged = received.asFlux().filter { it == message.id }.next().toFuture()
        bus.send(message).block(TIMEOUT)
        acknowledged.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun connectedClients(): Long {
        val connection: ReactiveRedisConnection = connectionFactory.reactiveConnection
        try {
            return connection.serverCommands().info("clients").block(TIMEOUT)!!
                .getProperty("connected_clients").toLong()
        } finally {
            connection.close()
        }
    }

    /** Waits until Redis counts [expected] clients: a closed connection leaves the count asynchronously. */
    private fun awaitConnectedClients(expected: Long) {
        val deadline = System.nanoTime() + TIMEOUT.toNanos()
        var clients = connectedClients()
        while (clients != expected && System.nanoTime() < deadline) {
            Mono.delay(Duration.ofMillis(50)).block()
            clients = connectedClients()
        }
        clients.assert().isEqualTo(expected)
    }

    private fun startReceiving(bus: RedisCommandBus, group: String = generateGlobalId()): Sinks.Many<String> {
        val received = Sinks.many().multicast().directBestEffort<String>()
        val receiver = bus.receiver(MessageSubscription(requiredNamedAggregate<MockCreateAggregate>(), group))
        receiving = receiver.openedMessages()
            .concatMap { exchange: ServerCommandExchange<*> ->
                exchange.acknowledge().doFinally { received.tryEmitNext(exchange.message.id) }
            }
            .subscribe()
        receiver.readiness.block(TIMEOUT)
        return received
    }

    private fun connectionsReceived(): Long {
        val connection: ReactiveRedisConnection = connectionFactory.reactiveConnection
        try {
            return connection.serverCommands().info("stats").block(TIMEOUT)!!
                .getProperty("total_connections_received").toLong()
        } finally {
            connection.close()
        }
    }

    private fun createMessage(): CommandMessage<*> = MockCreateAggregate(
        id = GlobalIdGenerator.generateAsString(),
        data = GlobalIdGenerator.generateAsString(),
    ).toCommandMessage()

    private companion object {
        const val ROUND_TRIPS = 500
        // The stream's own connection for its blocking reads, opened by its first read.
        const val MAX_NEW_CONNECTIONS = 2L
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
