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
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import reactor.core.Disposable
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * A Redis outage shorter than the receive retry policy must not end the receive stream (and with it the dispatcher
 * and the runtime). The Redis container is paused for 5 s; commands time out after 1 s meanwhile.
 *
 * The container is this test's own: pausing the shared one would stall the other Redis tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisReceiveRetryTest {
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
        val clientConfiguration = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(1))
            .build()
        connectionFactory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379)),
            clientConfiguration,
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
    fun `the receive stream survives a five second Redis pause`() {
        val bus = bus(TransportFailurePolicy(TransportFailurePolicy.receiveRetry(minBackoff = Duration.ofSeconds(1))))
        val received = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable>()
        startReceiving(bus, received, failure)
        val before = createMessage()
        bus.send(before).block(Duration.ofSeconds(5))
        awaitReceived(received, before)

        pauseRedis(Duration.ofSeconds(5))

        val after = createMessage()
        bus.send(after).block(Duration.ofSeconds(10))
        awaitReceived(received, after)
        failure.get().assert().isNull()
    }

    @Test
    fun `without receive retries the same pause ends the receive stream`() {
        val bus = bus(TransportFailurePolicy(TransportFailurePolicy.receiveRetry(maxAttempts = 0)))
        val received = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable>()
        startReceiving(bus, received, failure)

        pauseRedis(Duration.ofSeconds(5))

        failure.get().assert().isNotNull()
    }

    private fun bus(failurePolicy: TransportFailurePolicy) = RedisCommandBus(
        redisTemplate = redisTemplate,
        pollTimeout = Duration.ofMillis(200),
        recoveryOptions = RedisStreamRecoveryOptions.DISABLED,
        failurePolicy = failurePolicy,
    )

    private fun startReceiving(
        bus: RedisCommandBus,
        received: MutableList<String>,
        failure: AtomicReference<Throwable>,
    ) {
        val receiver = bus.receiver(MessageSubscription(requiredNamedAggregate<MockCreateAggregate>(), generateGlobalId()))
        receiving = receiver.openedMessages()
            .flatMap { exchange: ServerCommandExchange<*> ->
                received.add(exchange.message.id)
                exchange.acknowledge()
            }
            .subscribe({}, { failure.set(it) })
        receiver.readiness.block(Duration.ofSeconds(10))
    }

    private fun pauseRedis(duration: Duration) {
        val docker = DockerClientFactory.instance().client()
        docker.pauseContainerCmd(redis.containerId).exec()
        try {
            Mono.delay(duration).block()
        } finally {
            docker.unpauseContainerCmd(redis.containerId).exec()
        }
    }

    private fun awaitReceived(received: List<String>, message: CommandMessage<*>) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (message.id !in received && System.nanoTime() < deadline) {
            Mono.delay(Duration.ofMillis(50)).block()
        }
        received.assert().contains(message.id)
    }

    private fun createMessage(): CommandMessage<*> = MockCreateAggregate(
        id = GlobalIdGenerator.generateAsString(),
        data = GlobalIdGenerator.generateAsString(),
    ).toCommandMessage()
}
