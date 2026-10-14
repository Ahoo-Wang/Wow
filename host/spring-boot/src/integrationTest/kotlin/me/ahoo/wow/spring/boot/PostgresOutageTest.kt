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
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import javax.sql.DataSource

/**
 * The application's `DataSource` for an outage: short connection, validation and socket timeouts, so a database
 * that does not answer fails a call within seconds instead of holding it.
 */
@Configuration(proxyBeanMethods = false)
class OutagePostgresDataSource {
    @Bean(destroyMethod = "close")
    fun dataSource(environment: Environment): DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = environment.getRequiredProperty("test.postgres.url")
            username = environment.getRequiredProperty("test.postgres.username")
            password = environment.getRequiredProperty("test.postgres.password")
            maximumPoolSize = POOL_SIZE
            connectionTimeout = CONNECTION_TIMEOUT_MS
            validationTimeout = VALIDATION_TIMEOUT_MS
            addDataSourceProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS)
            addDataSourceProperty("connectTimeout", SOCKET_TIMEOUT_SECONDS)
            addDataSourceProperty("loginTimeout", SOCKET_TIMEOUT_SECONDS)
        },
    )

    private companion object {
        const val POOL_SIZE = 8
        const val CONNECTION_TIMEOUT_MS = 1000L
        const val VALIDATION_TIMEOUT_MS = 500L
        const val SOCKET_TIMEOUT_SECONDS = "2"
    }
}

/**
 * A command admitted while PostgreSQL does not answer (C.1 finding F2): its answer is the retryable
 * `wow.storage.unavailable` problem, 503 with `Retry-After` and the command's FAILED result, not a bare 500; once the
 * database is back, the same request id commits once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresOutageTest {
    private val postgres = WowContainers.newPostgres().also { it.start() }
    private val host = startHost(
        "wow.log.partitions" to 2,
        "test.postgres.url" to postgres.jdbcUrl,
        "test.postgres.username" to postgres.username,
        "test.postgres.password" to postgres.password,
        sources = listOf(HostTestApp::class.java, OutagePostgresDataSource::class.java),
    )

    @AfterAll
    fun close() {
        host.close()
        postgres.stop()
    }

    @Test
    fun `a command admitted while PostgreSQL is paused should answer the retryable storage error`() {
        val id = "outage-account"
        host.post("/bank/account/$id/open-account", """{"owner":"olga"}""", COMMITTED).status.assert().isEqualTo(200)
        val deposit = COMMITTED + (WowHeaders.REQUEST_ID to "outage-deposit")

        postgres.dockerClient.pauseContainerCmd(postgres.containerId).exec()
        val failed = try {
            host.post("/bank/account/$id/deposit", """{"amount":7}""", deposit)
        } finally {
            postgres.dockerClient.unpauseContainerCmd(postgres.containerId).exec()
        }

        failed.status.assert().isEqualTo(503)
        failed.contentType.assert().isEqualTo("application/problem+json")
        failed.headers.getFirst(HttpHeaders.RETRY_AFTER).assert().isEqualTo("1")
        val problem = failed.body!!
        problem["code"].asString().assert().isEqualTo(FrameworkErrors.STORAGE_UNAVAILABLE)
        problem["type"].asString().assert().isEqualTo("urn:wow:error:${FrameworkErrors.STORAGE_UNAVAILABLE}")
        problem["result"]["status"].asString().assert().isEqualTo("FAILED")
        problem["result"]["requestId"].asString().assert().isEqualTo("outage-deposit")
        problem["result"]["error"]["code"].asString().assert().isEqualTo(FrameworkErrors.STORAGE_UNAVAILABLE)

        // The client's retry with the same request id, once the database answers again, commits it once.
        val retried = retry { host.post("/bank/account/$id/deposit", """{"amount":7}""", deposit) }
        retried.status.assert().isEqualTo(200)
        retried.body!!["version"].asInt().assert().isEqualTo(2)
        val again = host.post("/bank/account/$id/deposit", """{"amount":7}""", deposit)
        again.body!!["duplicate"].asBoolean().assert().isTrue()
        again.body!!["version"].asInt().assert().isEqualTo(2)
    }

    /** [send]'s answer, sent again while it is the retryable storage error and the deadline has not passed. */
    private fun retry(send: () -> Answer): Answer {
        val deadline = System.currentTimeMillis() + RECOVERY_TIMEOUT
        var answer = send()
        while (answer.status == UNAVAILABLE && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL)
            answer = send()
        }
        return answer
    }

    private companion object {
        val COMMITTED = mapOf(WowHeaders.WAIT to "committed")
        const val UNAVAILABLE = 503
        const val RECOVERY_TIMEOUT = 30_000L
        const val POLL = 200L
    }
}
