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
import me.ahoo.wow.client.wire.WowHeaders
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.query.QueryBulkhead
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * A HikariCP pool whose query statements wait at a [gate] while it is set, holding their connections as a slow query
 * does: a query transaction is the one that starts with `SET LOCAL statement_timeout`. It says its size and the threads
 * awaiting a connection as HikariCP does, so the store derives its queries' bound and its write pressure from it.
 */
class GatedDataSource(private val pool: HikariDataSource) : DataSource by pool, AutoCloseable {
    /** While set, every query waits until it is counted down. */
    val gate = AtomicReference<CountDownLatch?>()

    /** The queries waiting at the [gate] now. */
    val held = AtomicInteger()

    /** The queries run through this pool. */
    val queries = AtomicInteger()

    fun getMaximumPoolSize(): Int = pool.maximumPoolSize

    /** HikariCP's pool, which says how many threads await a connection (decision D252). */
    fun getHikariPoolMXBean(): Any? = pool.hikariPoolMXBean

    override fun getConnection(): Connection = gated(pool.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        gated(pool.getConnection(username, password))

    override fun close() = pool.close()

    private fun gated(connection: Connection): Connection = proxy(connection) { method, args ->
        val result = method.invoke(connection, *args)
        if (method.name == "createStatement") gated(result as Statement) else result
    }

    private fun gated(statement: Statement): Statement = proxy(statement) { method, args ->
        if (method.name == "execute" && (args.firstOrNull() as? String)?.startsWith(QUERY_START) == true) {
            queries.incrementAndGet()
            gate.get()?.let { latch ->
                held.incrementAndGet()
                try {
                    latch.await(HOLD_SECONDS, TimeUnit.SECONDS)
                } finally {
                    held.decrementAndGet()
                }
            }
        }
        method.invoke(statement, *args)
    }

    private inline fun <reified T : Any> proxy(target: T, crossinline call: (java.lang.reflect.Method, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { _, method, args ->
            try {
                call(method, args ?: emptyArray())
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as T

    private companion object {
        const val QUERY_START = "SET LOCAL statement_timeout"
        const val HOLD_SECONDS = 30L
    }
}

/** A gated pool of [POOL_SIZE] connections, shared by the appends and the queries. */
@Configuration(proxyBeanMethods = false)
class SharedGatedDataSource {
    @Bean(destroyMethod = "close")
    fun dataSource(environment: Environment): GatedDataSource = GatedDataSource(pool(environment, POOL_SIZE))

    companion object {
        const val POOL_SIZE = 4
    }
}

/** An appends' pool and a gated pool of the queries' own (`wow.stores.<name>.query-data-source`). */
@Configuration(proxyBeanMethods = false)
class SeparateGatedDataSources {
    @Bean(destroyMethod = "close")
    fun appends(environment: Environment): HikariDataSource = pool(environment, APPENDS)

    @Bean(destroyMethod = "close")
    fun reads(environment: Environment): GatedDataSource = GatedDataSource(pool(environment, READS))

    companion object {
        const val APPENDS = 4
        const val READS = 3
    }
}

internal fun pool(environment: Environment, size: Int) = HikariDataSource(
    HikariConfig().apply {
        jdbcUrl = environment.getRequiredProperty("test.postgres.url")
        username = environment.getRequiredProperty("test.postgres.username")
        password = environment.getRequiredProperty("test.postgres.password")
        maximumPoolSize = size
        // Without the bulkhead, a command waiting for a connection the queries hold fails after this.
        connectionTimeout = CONNECTION_TIMEOUT_MS
    },
)

private const val CONNECTION_TIMEOUT_MS = 3000L

/**
 * Queries on PostgreSQL apart from the appends (review R3.4-2): with a tiny pool the appends share, as many list
 * queries as there are connections, each holding its connection, do not delay a command beyond a bound, since the
 * store's bulkhead lets a quarter of the pool (at least two) run and answers the rest `wow.query.busy` once they have
 * waited `wow.query.max-wait` in its queue (decision D247) for a permit the held queries never give back; with a
 * `query-data-source` of their own, the queries never take an append's connection and may use their whole pool.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresQueryIsolationTest {
    private val postgres = WowContainers.newPostgres().also { it.start() }
    private val database = arrayOf<Pair<String, Any>>(
        "wow.log.partitions" to 2,
        "test.postgres.url" to postgres.jdbcUrl,
        "test.postgres.username" to postgres.username,
        "test.postgres.password" to postgres.password,
    )
    private val clients = Executors.newFixedThreadPool(CLIENTS)

    @AfterAll
    fun close() {
        clients.shutdownNow()
        postgres.stop()
    }

    @Test
    fun `list queries holding a shared pool's connections should not delay a command beyond a bound`() {
        startHost(
            *database,
            sources = listOf(HostTestApp::class.java, SharedGatedDataSource::class.java),
        ).use { host ->
            val pool = host.context.getBean(GatedDataSource::class.java)
            val id = host.openAccount("shared")
            host.context.getBean(QueryStores::class.java).bulkheads.values.single().maxConcurrent
                .assert().isEqualTo(2)
            isolated(host, pool, id, admitted = 2)
        }
    }

    @Test
    fun `queries on a pool of their own should never take an append's connection`() {
        startHost(
            *database,
            "wow.stores.main.type" to "postgres",
            "wow.stores.main.data-source" to "appends",
            "wow.stores.main.query-data-source" to "reads",
            sources = listOf(HostTestApp::class.java, SeparateGatedDataSources::class.java),
        ).use { host ->
            val reads = host.context.getBean(GatedDataSource::class.java)
            val id = host.openAccount("separate")
            host.context.getBean(QueryStores::class.java).bulkheads.getValue("main").maxConcurrent
                .assert().isEqualTo(SeparateGatedDataSources.READS)
            isolated(host, reads, id, admitted = SeparateGatedDataSources.READS)
            reads.queries.get().assert().isGreaterThanOrEqualTo(SeparateGatedDataSources.READS)
        }
    }

    /**
     * Sends [CLIENTS] list queries while [pool]'s queries are held, of which [admitted] run and hold their
     * connections and the rest are `wow.query.busy`; then a command on [id] commits within [COMMAND_BOUND_MS].
     */
    private fun isolated(host: Host, pool: GatedDataSource, id: String, admitted: Int) {
        val gate = CountDownLatch(1)
        pool.gate.set(gate)
        val sent = System.nanoTime()
        val answers = (1..CLIENTS).map {
            clients.submit<Answer> {
                host.post("/bank/account/snapshot/query", """{"kind":"list","filter":{"op":"MATCH_ALL"},"limit":10}""")
            }
        }
        try {
            eventually { pool.held.get() == admitted && answers.count { it.isDone } == CLIENTS - admitted }
            // The others waited in the queue for the default max-wait (D247), then were refused.
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent)
                .assert().isGreaterThanOrEqualTo(QueryBulkhead.DEFAULT_MAX_WAIT.inWholeMilliseconds)
            answers.filter { it.isDone }.map { it.get() }.forEach { answer ->
                answer.status.assert().isEqualTo(429)
                answer.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.QUERY_BUSY)
            }
            val started = System.nanoTime()
            val deposited = host.post("/bank/account/$id/deposit", """{"amount":1}""", COMMITTED)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            deposited.status.assert().isEqualTo(200)
            elapsedMs.assert().isLessThan(COMMAND_BOUND_MS)
            pool.held.get().assert().isEqualTo(admitted)
        } finally {
            pool.gate.set(null)
            gate.countDown()
        }
        answers.map { it.get(ANSWER_SECONDS, TimeUnit.SECONDS) }.count { it.status == 200 }.assert().isEqualTo(admitted)
    }

    private fun Host.openAccount(owner: String): String {
        val opened = post("/bank/account/open-account", """{"owner":"$owner"}""", COMMITTED)
        opened.status.assert().isEqualTo(200)
        return opened.body!!["ref"]["id"].asString()
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ANSWER_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "The condition did not hold in time." }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        /** As many clients as the shared pool has connections, and more. */
        const val CLIENTS = 6

        /** Below the pools' connection timeout: a command that waited for a connection would fail after it. */
        const val COMMAND_BOUND_MS = 2000L
        const val ANSWER_SECONDS = 20L
        const val POLL_MS = 20L
        val COMMITTED = mapOf(WowHeaders.WAIT to "committed")
    }
}
