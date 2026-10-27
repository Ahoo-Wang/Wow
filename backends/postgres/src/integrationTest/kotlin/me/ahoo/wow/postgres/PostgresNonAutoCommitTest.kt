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

package me.ahoo.wow.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.postgres.ledger.PostgresFailureLedger
import me.ahoo.wow.postgres.reservation.PostgresReservationStore
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import me.ahoo.wow.postgres.subscription.PostgresLeaseStore
import me.ahoo.wow.postgres.timer.PostgresTimerStore
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.tck.containers.WowContainers
import me.ahoo.wow.tck.ledger.FailureLedgerSpec
import me.ahoo.wow.tck.log.EventLogSpec
import me.ahoo.wow.tck.reservation.ReservationStoreSpec
import me.ahoo.wow.tck.snapshot.SnapshotStoreSpec
import me.ahoo.wow.tck.subscription.CheckpointStoreSpec
import me.ahoo.wow.tck.subscription.LeaseStoreSpec
import me.ahoo.wow.tck.timer.TimerStoreSpec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

/**
 * Finding R2.2-1: every PostgreSQL store over a pool configured with `auto-commit=false`, as a Spring Boot host tuned
 * for Hibernate shares it. HikariCP rolls back what is left uncommitted when a connection is returned, so a store that
 * relied on the pool's default reported writes that were then lost: the TCK specs, which read back what they wrote,
 * run here over such a pool, and every connection a store borrowed must go back in the autocommit mode it was lent in.
 */
class PostgresNonAutoCommitTest {
    @Test
    fun `the pool lends connections without autocommit`() {
        pool.connection.use { it.autoCommit.assert().isFalse() }
    }

    @Test
    fun `writes are visible from another pool, and the connections go back as they were lent`(): Unit = runBlocking {
        val checkpoints = PostgresCheckpointStore(dataSource).also { it.createSchema() }
        val group = PostgresFixture.newContext("non-autocommit")
        checkpoints.save(group, "subscription", 0, 42)
        PostgresCheckpointStore(PostgresFixture.dataSource).load(group, "subscription", 0).assert().isEqualTo(42L)

        val leases = PostgresLeaseStore(dataSource).also { it.createSchema() }
        leases.acquire(group, 0, "node-a", 30.seconds).assert().isNotNull()
        PostgresLeaseStore(PostgresFixture.dataSource).acquire(group, 0, "node-b", 30.seconds).assert().isNull()
        returned.assertRestored()
    }

    @Test
    fun `creating a schema leaves the connection in the mode it was lent in, also when it fails`() {
        pool.connection.use { connection ->
            PostgresFormat.creating(connection, "t") { }
            connection.autoCommit.assert().isFalse()
            assertThrownBy<IllegalStateException> { PostgresFormat.creating(connection, "t") { error("boom") } }
            connection.autoCommit.assert().isFalse()
        }
        PostgresFixture.dataSource.connection.use { connection ->
            PostgresFormat.creating(connection, "t") { }
            connection.autoCommit.assert().isTrue()
        }
    }

    @Nested
    inner class EventLogs : EventLogSpec() {
        override fun createLog(context: String, partitions: Int): EventLog =
            PostgresEventLog(dataSource, context, partitions).also { it.createSchema() }

        override fun createSnapshots(context: String): SnapshotStore = PostgresSnapshotStore(dataSource, context)


        override fun snapshotTailsOf(log: EventLog, snapshots: SnapshotStore): SnapshotTailReader =

            (log as PostgresEventLog).snapshotTailsWith(snapshots as PostgresSnapshotStore)

        @AfterEach
        fun restored() = returned.assertRestored()
    }

    @Nested
    inner class Snapshots : SnapshotStoreSpec() {
        override fun createStore(context: String): SnapshotStore =
            PostgresSnapshotStore(dataSource, context).also { it.createSchema() }

        @AfterEach
        fun restored() = returned.assertRestored()
    }

    @Nested
    inner class Checkpoints : CheckpointStoreSpec() {
        override fun createStore(): CheckpointStore = PostgresCheckpointStore(dataSource).also { it.createSchema() }

        @AfterEach
        fun restored() = returned.assertRestored()
    }

    @Nested
    inner class Leases : LeaseStoreSpec() {
        override fun createStore(clock: Clock): LeaseStore =
            PostgresLeaseStore(dataSource, clock).also { it.createSchema() }

        @AfterEach
        fun restored() = returned.assertRestored()
    }

    @Nested
    inner class Timers : TimerStoreSpec() {
        override val context: String = PostgresFixture.newContext("non-autocommit-timer")

        override fun createStore(): TimerStore = PostgresTimerStore(dataSource, context).also { it.createSchema() }

        @AfterEach
        fun restored() = returned.assertRestored()
    }

    @Nested
    inner class Ledgers : FailureLedgerSpec() {
        override fun createLedger(): FailureLedger =
            PostgresFailureLedger(dataSource, "ledger_non_autocommit").also { it.createSchema() }

        @AfterEach
        fun restored() = returned.assertRestored()
    }

    @Nested
    inner class Reservations : ReservationStoreSpec() {
        override fun createStore(clock: Clock): ReservationStore =
            PostgresReservationStore(dataSource, clock).also { it.createSchema() }

        @AfterEach
        fun restored() = returned.assertRestored()
    }

    /** Counts the connections returned with autocommit on, though the pool lent them with it off. */
    class Returned {
        private val changed = AtomicInteger()

        fun record(connection: Connection) {
            if (!connection.isClosed && connection.autoCommit) {
                changed.incrementAndGet()
            }
        }

        fun assertRestored() {
            changed.getAndSet(0).assert().describedAs("connections returned with autocommit on").isEqualTo(0)
        }
    }

    private companion object {
        val pool: HikariDataSource by lazy {
            val container = WowContainers.postgres
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = container.jdbcUrl
                    username = container.username
                    password = container.password
                    maximumPoolSize = POOL_SIZE
                    isAutoCommit = false
                },
            )
        }

        val returned = Returned()

        /** [pool], recording the autocommit mode of each connection as it is returned. */
        val dataSource: DataSource by lazy { recording(DataSource::class.java, pool) }

        private fun <T : Any> recording(type: Class<T>, delegate: T): T = type.cast(
            Proxy.newProxyInstance(
                type.classLoader,
                arrayOf(type),
                InvocationHandler { _, method, args ->
                    if (method.name == "close" && delegate is Connection) {
                        returned.record(delegate)
                    }
                    val result = try {
                        method.invoke(delegate, *(args ?: emptyArray()))
                    } catch (failure: InvocationTargetException) {
                        throw failure.targetException
                    }
                    if (method.name == "getConnection") recording(Connection::class.java, result as Connection) else result
                },
            ),
        )

        // Each lease store pins one connection for its lifetime (decision D270), and the lease specs open one per case.
        const val POOL_SIZE = 32
    }
}
