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

package me.ahoo.wow.redis

import io.lettuce.core.RedisURI
import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.DefaultClientResources
import io.lettuce.core.resource.SocketAddressResolver
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.toForever
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.redis.eventsourcing.RedisEventStore
import me.ahoo.wow.redis.prepare.RedisPrepareKeyFactory
import me.ahoo.wow.tck.container.ContainerImages
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.connection.RedisClusterConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.utility.DockerImageName
import reactor.kotlin.test.test
import java.net.InetSocketAddress
import java.net.SocketAddress

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisClusterPersistenceTest {
    private val network = Network.newNetwork()
    private val nodes = (1..CLUSTER_NODE_COUNT).map(::redisNode)
    private lateinit var clientResources: ClientResources
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: ReactiveStringRedisTemplate

    @BeforeAll
    fun startCluster() {
        nodes.forEach(GenericContainer<*>::start)
        val createResult = nodes.first().execInContainer(
            "redis-cli",
            "--cluster",
            "create",
            *nodes.indices.map { index -> "redis-${index + 1}:6379" }.toTypedArray(),
            "--cluster-replicas",
            "0",
            "--cluster-yes",
        )
        check(createResult.exitCode == 0) {
            "Failed to create Redis Cluster: ${createResult.stderr}\n${createResult.stdout}"
        }

        val advertisedNodes = nodes.mapIndexed { index, container ->
            "redis-${index + 1}" to InetSocketAddress(container.host, container.getMappedPort(REDIS_PORT))
        }.toMap()
        clientResources = DefaultClientResources.builder()
            .socketAddressResolver(
                object : SocketAddressResolver() {
                    override fun resolve(redisURI: RedisURI): SocketAddress {
                        return advertisedNodes[redisURI.host] ?: super.resolve(redisURI)
                    }
                }
            ).build()
        val clientConfiguration = LettuceClientConfiguration.builder()
            .clientResources(clientResources)
            .build()
        val clusterConfiguration = RedisClusterConfiguration(
            nodes.map { container -> "${container.host}:${container.getMappedPort(REDIS_PORT)}" }
        )
        connectionFactory = LettuceConnectionFactory(clusterConfiguration, clientConfiguration)
        connectionFactory.afterPropertiesSet()
        redisTemplate = ReactiveStringRedisTemplate(connectionFactory)
    }

    @AfterAll
    fun stopCluster() {
        if (::connectionFactory.isInitialized) {
            connectionFactory.destroy()
        }
        if (::clientResources.isInitialized) {
            clientResources.shutdown().syncUninterruptibly()
        }
        nodes.reversed().forEach(GenericContainer<*>::stop)
        network.close()
    }

    @Test
    fun `event append script should execute atomically on Redis Cluster`() {
        val aggregateId = MaterializedNamedAggregate("cluster-context", "order")
            .aggregateId("order:{雪}", "tenant:{east}")
        val eventStream = generateEventStream(aggregateId, eventCount = 1)
        val eventStore: EventStore = RedisEventStore(redisTemplate)

        eventStore.append(eventStream)
            .then(eventStore.existsRequestId(aggregateId, eventStream.requestId))
            .test()
            .expectNext(true)
            .verifyComplete()
    }

    @Test
    fun `prepare scripts should execute using the explicit canonical key on Redis Cluster`() {
        val factory = RedisPrepareKeyFactory(redisTemplate)
        val username = factory.create("username:{雪}", String::class.java)
        val email = factory.create("email:{雪}", String::class.java)

        username.prepare("same:{key}", "alice".toForever())
            .then(email.prepare("same:{key}", "alice@example.com".toForever()))
            .then(username.get("same:{key}"))
            .test()
            .expectNext("alice")
            .verifyComplete()
    }

    private fun redisNode(index: Int): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(ContainerImages.REDIS))
            .withNetwork(network)
            .withNetworkAliases("redis-$index")
            .withExposedPorts(REDIS_PORT)
            .withCommand(
                "redis-server",
                "--port",
                REDIS_PORT.toString(),
                "--cluster-enabled",
                "yes",
                "--cluster-config-file",
                "nodes.conf",
                "--cluster-node-timeout",
                "5000",
                "--cluster-announce-hostname",
                "redis-$index",
                "--cluster-preferred-endpoint-type",
                "hostname",
                "--appendonly",
                "no",
                "--protected-mode",
                "no",
            )

    companion object {
        private const val CLUSTER_NODE_COUNT = 3
        private const val REDIS_PORT = 6379
    }
}
