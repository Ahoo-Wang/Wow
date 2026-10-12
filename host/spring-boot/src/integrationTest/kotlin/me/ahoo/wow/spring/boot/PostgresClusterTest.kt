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

package me.ahoo.wow.spring.boot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.runtime.admission.Caller
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.sql.DataSource

/** The application's `DataSource`: a pool of its own on the database the test names. */
@Configuration(proxyBeanMethods = false)
class PostgresDataSource {
    @Bean(destroyMethod = "close")
    fun dataSource(environment: Environment): DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = environment.getRequiredProperty("test.postgres.url")
            username = environment.getRequiredProperty("test.postgres.username")
            password = environment.getRequiredProperty("test.postgres.password")
            maximumPoolSize = POOL_SIZE
        },
    )

    private companion object {
        const val POOL_SIZE = 8
    }
}

/**
 * Two hosts of one service in one JVM, on one PostgreSQL database (roadmap 5b.3): each serves its public port and its
 * internal port, so a command sent to one node runs on the owner of its partition, forwarded over real h2c, and the
 * owner's snapshot subscription signals the wait back to the node the command arrived at.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresClusterTest {
    private val postgres = PostgreSQLContainer("postgres:17-alpine").also { it.start() }
    private val hosts = listOf("node-a", "node-b").map { node ->
        startHost(
            "wow.node.id" to node,
            "wow.log.partitions" to PARTITIONS,
            "test.postgres.url" to postgres.jdbcUrl,
            "test.postgres.username" to postgres.username,
            "test.postgres.password" to postgres.password,
            sources = listOf(HostTestApp::class.java, PostgresDataSource::class.java),
        )
    }
    private val first = hosts[0]
    private val second = hosts[1]

    @AfterAll
    fun close() {
        hosts.forEach { it.close() }
        postgres.stop()
    }

    @Test
    fun `the hosts should run on PostgreSQL with partitioned routing`() {
        hosts.forEach { host ->
            host.context.getBean(HostBackends::class.java).kind.assert().isEqualTo(BackendKind.POSTGRES)
            host.node.options.routing.assert().isEqualTo(Routing.PARTITIONED)
            host.node.options.appendQueue.enabled.assert().isFalse()
        }
    }

    @Test
    fun `a command sent to one node should run on the owner and its wait end on the sender`() {
        val (warmUp, id) = idsOwnedBySecond(2)
        // The forward timeout (one second, D80) is for an owner that is warm; this JVM, both hosts in it, has decided
        // no command yet. One decided on its owner first, so the forward below is not its first decision. The
        // connection to the owner was opened when the first node's routes learned it (ClusterChannel.connect).
        second.post("/bank/account/$warmUp/open-account", """{"owner":"oleg"}""").status.assert().isEqualTo(200)
        val opened = first.post("/bank/account/$id/open-account", """{"owner":"olga"}""")
        opened.status.assert().isEqualTo(200)
        opened.body!!["hop"]["subscription"].asString().assert().isEqualTo("snapshot")
        opened.body!!["status"].asString().assert().isEqualTo("SUCCEEDED")
        second.decided.ids.assert().contains(id)
        first.decided.ids.assert().doesNotContain(id)

        first.post("/bank/account/$id/deposit", """{"amount":7}""").status.assert().isEqualTo(200)
        val state = first.get("/bank/account/$id/state")
        state.status.assert().isEqualTo(200)
        state.body!!["state"]["balance"].asLong().assert().isEqualTo(7)
        state.body!!["version"].asInt().assert().isEqualTo(2)
    }

    @Test
    fun `the internal port should speak h2c`() {
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()
        val request = HttpRequest.newBuilder(InternalHttp.uri("127.0.0.1:${second.internalPort}", InternalHttp.SIGNALS_PATH))
            .header("Content-Type", InternalHttp.JSON)
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        response.version().assert().isEqualTo(HttpClient.Version.HTTP_2)
        response.statusCode().assert().isEqualTo(400)
    }

    /** [count] aggregate ids whose partitions the second node owns and the first forwards to it, once they agree. */
    private fun idsOwnedBySecond(count: Int): List<String> {
        val type = AggregateType(BANK, "account")
        val deadline = System.currentTimeMillis() + OWNERSHIP_TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            val owned = second.node.context(BANK).ownership.owned
            val routes = first.node.context(BANK).ownership
            val ids = (0 until CANDIDATES).map { "acc-$it" }.filter { candidate ->
                val partition = Partitioner.partitionOf(type.ref(Caller.DEFAULT_TENANT, candidate), PARTITIONS)
                partition in owned && routes.forwardTarget(partition)?.node == "node-b"
            }
            if (ids.size >= count && first.node.context(BANK).ownership.owned.isNotEmpty()) {
                return ids.take(count)
            }
            Thread.sleep(POLL)
        }
        error("The nodes did not share the partitions in time.")
    }

    private companion object {
        const val PARTITIONS = 8
        const val CANDIDATES = 200
        const val OWNERSHIP_TIMEOUT = 30_000L
        const val POLL = 100L
    }
}
