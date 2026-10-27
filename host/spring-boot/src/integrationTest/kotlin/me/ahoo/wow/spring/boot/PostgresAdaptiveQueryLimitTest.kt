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

import com.zaxxer.hikari.HikariPoolMXBean
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.query.admission.AdaptiveQueryLimit
import me.ahoo.wow.query.admission.AdaptiveQueryLimits
import me.ahoo.wow.query.admission.QueryBulkhead
import me.ahoo.wow.query.store.QueryStores
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A gated pool of [POOL_SIZE] connections, shared by the appends and the queries. */
@Configuration(proxyBeanMethods = false)
class LargerGatedDataSource {
    @Bean(destroyMethod = "close")
    fun dataSource(environment: Environment): GatedDataSource = GatedDataSource(pool(environment, POOL_SIZE))

    companion object {
        const val POOL_SIZE = 8

        /** The connection the lease store keeps for itself (decision D270): the others are the appends' and queries'. */
        const val LEASE_CONNECTIONS = 1
    }
}

/**
 * The PostgreSQL query bound adapting to the write pressure (decision D252), on a pool of eight connections the
 * appends share: the bound starts at two (a quarter of the pool), climbs to four (half of it) while the writes are calm
 * and queries are held back, halves down to one while appends wait for a connection, cancelling none of the running
 * queries, keeps a command fast once the held queries are replaced by fewer, and climbs again once the writes are calm.
 * The pressure is HikariCP's threads awaiting a connection: the test takes the connections the queries leave and
 * waits for one more, as a burst of appends would.
 *
 * The test ends each period itself ([AdaptiveQueryLimit.evaluate]), the node's own evaluation an hour away, so the
 * bound moves only where the test says, however slow the runner; it measures nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresAdaptiveQueryLimitTest {
    private val postgres = WowContainers.newPostgres().also { it.start() }
    private val clients = Executors.newCachedThreadPool()

    @AfterAll
    fun close() {
        clients.shutdownNow()
        postgres.stop()
    }

    @Test
    fun `the bound should fall while appends wait for a connection and climb while the writes are calm`() {
        startHost(
            "wow.log.partitions" to 2,
            "test.postgres.url" to postgres.jdbcUrl,
            "test.postgres.username" to postgres.username,
            "test.postgres.password" to postgres.password,
            sources = listOf(HostTestApp::class.java, LargerGatedDataSource::class.java),
        ).use { host ->
            val pool = host.context.getBean(GatedDataSource::class.java)
            val id = host.openAccount("adaptive")
            val limit = host.context.getBean(QueryStores::class.java).limits.single()
            // The node's own evaluations stopped: the test ends every period itself.
            host.context.getBean(AdaptiveQueryLimits::class.java).close()
            val bulkhead = limit.bulkhead
            bulkhead.maxConcurrent.assert().isEqualTo(2)
            bulkhead.ceiling.assert().isEqualTo(LargerGatedDataSource.POOL_SIZE / 2)

            val held = CountDownLatch(1)
            pool.gate.set(held)
            val queries = ArrayList<Future<Answer>>()
            try {
                // Calm writes, queries held back: the bound climbs to the ceiling, one more query running each time.
                climb(host, pool, limit, queries, to = bulkhead.ceiling)
                pool.held.get().assert().isEqualTo(bulkhead.ceiling)

                // Appends wait for a connection: the bound halves, down to the floor; the held queries run on.
                val free = LargerGatedDataSource.POOL_SIZE - LargerGatedDataSource.LEASE_CONNECTIONS - bulkhead.ceiling
                pressing(pool, free = free) {
                    // Each period ends while a thread awaits a connection: two in a row halve the bound, 4, 2, 1.
                    eventually {
                        eventually { pool.awaiting() >= 1 }
                        limit.evaluate()
                        bulkhead.limit == 1
                    }
                }
                // Nothing raised it meanwhile: no query was held back in a calm period.
                limit.decreases.assert().isEqualTo(2)
                pool.held.get().assert().isEqualTo(bulkhead.ceiling)
                bulkhead.running.assert().isEqualTo(bulkhead.ceiling)
            } finally {
                pool.gate.set(null)
                held.countDown()
            }
            queries.forEach { it.get(ANSWER_SECONDS, TimeUnit.SECONDS) }
            eventually { bulkhead.running == 0 }

            // Under the lowered bound, one query holds a connection and the rest are refused; a command is fast.
            val gate = CountDownLatch(1)
            pool.gate.set(gate)
            val again = (1..CLIENTS).map { clients.submit<Answer> { host.list() } }
            try {
                // No period ends meanwhile: one runs, against four before, and the rest are refused.
                eventually { pool.held.get() == 1 && again.count { it.isDone } == CLIENTS - 1 }
                bulkhead.limit.assert().isEqualTo(1)
                again.filter { it.isDone }.forEach {
                    it.get().body!!["code"].asString().assert().isEqualTo(FrameworkErrors.QUERY_BUSY)
                }
                val started = System.nanoTime()
                host.post("/bank/account/$id/deposit", """{"amount":1}""", COMMITTED).status.assert().isEqualTo(200)
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).assert().isLessThan(COMMAND_BOUND_MS)
                // Calm again, queries held back: the bound climbs back.
                climb(host, pool, limit, ArrayList(), to = 3)
            } finally {
                pool.gate.set(null)
                gate.countDown()
            }
            limit.pressed.assert().isFalse()
        }
    }

    /**
     * Raises [limit]'s bound to [to], one period at a time: while every permit is held, a query sent is refused,
     * which holds the bound back in the period the test then ends, so the bound rises by one; a query sent once it
     * rose holds the new permit at [pool]'s gate. Each step ends with as many queries held as the bound allows.
     */
    private fun climb(
        host: Host,
        pool: GatedDataSource,
        limit: AdaptiveQueryLimit,
        sent: MutableList<Future<Answer>>,
        to: Int,
    ) {
        val bulkhead = limit.bulkhead
        eventually { pool.held.get() == bulkhead.running }
        while (bulkhead.limit < to) {
            fill(host, pool, bulkhead, sent)
            val refused = clients.submit<Answer> { host.list() }.also { sent += it }
            refused.get(ANSWER_SECONDS, TimeUnit.SECONDS).status.assert().isEqualTo(429)
            val before = bulkhead.limit
            limit.evaluate()
            bulkhead.limit.assert().isEqualTo(before + 1)
        }
        fill(host, pool, bulkhead, sent)
    }

    /** Sends queries, one at a time, until as many wait at [pool]'s gate as [bulkhead]'s bound allows. */
    private fun fill(host: Host, pool: GatedDataSource, bulkhead: QueryBulkhead, sent: MutableList<Future<Answer>>) {
        while (bulkhead.running < bulkhead.limit) {
            val running = bulkhead.running
            val query = clients.submit<Answer> { host.list() }.also { sent += it }
            eventually { bulkhead.running > running || query.isDone }
            eventually { pool.held.get() == bulkhead.running }
        }
    }

    /** The threads awaiting a connection of this pool now, as HikariCP says. */
    private fun GatedDataSource.awaiting(): Int = (getHikariPoolMXBean() as HikariPoolMXBean).threadsAwaitingConnection

    /**
     * Runs [block] while the test holds [free] connections of [pool], those the queries leave, and a thread waits
     * for one more: HikariCP counts it as awaiting a connection, as an append would be.
     */
    private fun pressing(pool: GatedDataSource, free: Int, block: () -> Unit) {
        val taken = ArrayList<Connection>()
        val waiting = AtomicBoolean(true)
        try {
            repeat(free) { taken += pool.connection }
            val waiter = clients.submit {
                while (waiting.get()) {
                    try {
                        pool.connection.close()
                    } catch (_: SQLException) {
                        // The pool's connection timeout: wait again.
                    }
                }
            }
            try {
                block()
            } finally {
                waiting.set(false)
                taken.forEach { it.close() }
                taken.clear()
                waiter.get(ANSWER_SECONDS, TimeUnit.SECONDS)
            }
        } finally {
            taken.forEach { it.close() }
        }
    }

    private fun Host.list(): Answer =
        post("/bank/account/snapshot/query", """{"kind":"list","filter":{"op":"MATCH_ALL"},"limit":10}""")

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
        const val CLIENTS = 6

        /** Below the pool's connection timeout: a command that waited for a connection would fail after it. */
        const val COMMAND_BOUND_MS = 2000L
        const val ANSWER_SECONDS = 30L
        const val POLL_MS = 50L
        val COMMITTED = mapOf(WowHeaders.WAIT to "committed")
    }
}
