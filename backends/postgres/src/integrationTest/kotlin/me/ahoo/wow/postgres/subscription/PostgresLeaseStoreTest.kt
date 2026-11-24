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

package me.ahoo.wow.postgres.subscription

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.postgres.PostgresTimeouts
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.tck.subscription.LeaseStoreSpec
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class PostgresLeaseStoreTest : LeaseStoreSpec() {
    override fun createStore(clock: Clock): LeaseStore = PostgresLeaseStore(PostgresFixture.dataSource, clock)

    @Test
    fun `the store runs on one connection it keeps, and borrows another once that one fails`(): Unit = runBlocking {
        // Decision D270: the lease operations never wait behind the commands for a connection of the pool.
        val pool = Lending(PostgresFixture.dataSource)
        val store = PostgresLeaseStore(pool.dataSource)
        val group = "pinned-" + UUID.randomUUID()
        store.acquire(group, 0, "node-a", TTL)
        store.acquire(group, 1, "node-a", TTL)
        store.renew(group, listOf(0, 1), "node-a", TTL).map { it.partition }.assert().containsExactlyInAnyOrder(0, 1)
        store.heartbeat(group, "node-a", "10.0.0.1:9090", TTL)
        store.members(group).map { it.node }.assert().containsExactly("node-a")
        store.owners(group).size.assert().isEqualTo(2)
        pool.borrowed.get().assert().isEqualTo(1)

        // The kept connection breaks: the operation fails, and the next borrows another.
        pool.last!!.close()
        runCatching { store.renew(group, listOf(0), "node-a", TTL) }.isFailure.assert().isTrue()
        store.renew(group, listOf(0), "node-a", TTL).map { it.partition }.assert().containsExactly(0)
        store.release(group, 1, "node-a").assert().isTrue()
        pool.borrowed.get().assert().isEqualTo(2)
    }

    @Test
    fun `a lease call held by a row lock fails within its TTL, and the next borrows another connection`(): Unit =
        runBlocking {
            // The store's connections wait 5 s for a reply; the calls under test give their TTL, a second.
            val store = PostgresLeaseStore(PostgresTimeouts.timed(PostgresFixture.dataSource, OPERATION))
            val group = "bounded-" + UUID.randomUUID()
            store.acquire(group, 0, "node-a", TTL).assert().isNotNull()
            store.heartbeat(group, "node-a", "10.0.0.1:9090", TTL)
            PostgresFixture.dataSource.connection.use { locker ->
                locker.autoCommit = false
                try {
                    locker.lock("wow_lease WHERE subscription_group = ? AND partition = 0", group)
                    locker.lock("wow_lease_member WHERE subscription_group = ? AND node = 'node-a'", group)
                    within(SHORT) { store.acquire(group, 0, "node-a", SHORT) }
                    within(SHORT) { store.renew(group, listOf(0), "node-a", SHORT) }
                    within(SHORT) { store.heartbeat(group, "node-a", "10.0.0.1:9090", SHORT) }
                    // The connection each one lost is replaced: a call no lock holds still answers.
                    store.acquire(group, 1, "node-a", TTL)?.owner.assert().isEqualTo("node-a")
                } finally {
                    locker.rollback()
                }
            }
        }

    /** Takes the row lock of the rows of [rows] (a table and a condition on its group) until the transaction ends. */
    private fun Connection.lock(rows: String, group: String) {
        prepareStatement("SELECT 1 FROM $rows FOR UPDATE").use { statement ->
            statement.setString(1, group)
            statement.executeQuery().use { it.next().assert().isTrue() }
        }
    }

    /** [call] fails as the store unavailable within [ttl] and a margin, long before the connection's own timeout. */
    private fun within(ttl: Duration, call: suspend () -> Unit) {
        val started = TimeSource.Monotonic.markNow()
        assertThrownBy<StorageUnavailableException> { runBlocking { call() } }
        started.elapsedNow().assert().isLessThan(ttl + MARGIN)
    }

    /** [target] counting the connections it lends, the last of them kept as [last]. */
    private class Lending(target: DataSource) {
        val borrowed = AtomicInteger()

        @Volatile
        var last: Connection? = null

        val dataSource: DataSource = Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, method, args ->
            val result = method.invoke(target, *(args ?: emptyArray()))
            if (method.name == "getConnection") {
                borrowed.incrementAndGet()
                last = result as Connection
            }
            result
        } as DataSource
    }

    private companion object {
        val TTL = 10.seconds

        /** The TTL of the calls a lock holds, and the margin they may take past it. */
        val SHORT = 1.seconds
        val MARGIN = 1.5.seconds

        /** The store's own operation timeout in the bounded test: what a call without the bound would wait. */
        val OPERATION = 5.seconds

        init {
            PostgresLeaseStore(PostgresFixture.dataSource).createSchema()
        }
    }
}
