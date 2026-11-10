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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.postgres.subscription.PostgresLeaseStore
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.store.StoreConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A PostgreSQL store gives back the connection its leases keep when it is closed, and nothing of the pool's. */
class PostgresLeaseLifecycleTest {
    private val borrowed = mutableListOf<Connection>()
    private val source = mockk<DataSource>(relaxed = true) {
        every { connection } answers {
            val rows = mockk<ResultSet>(relaxed = true) { every { next() } returns false }
            val statement = mockk<PreparedStatement>(relaxed = true) { every { executeQuery() } returns rows }
            mockk<Connection>(relaxed = true) {
                every { autoCommit } returns true
                every { prepareStatement(any<String>()) } returns statement
            }.also { borrowed += it }
        }
    }

    @Test
    fun `each store closed should give back the one connection its leases kept, once`(): Unit = runBlocking {
        repeat(3) { index ->
            val store = PostgresStore(StoreConfig.of("lease-$index", "postgres"), source)
            store.leases.owners("service").assert().isEmpty()
            store.leases.owners("service").assert().isEmpty()
            store.close()
            store.close()
        }
        // One connection kept per store, each given back once when its store closed.
        borrowed.size.assert().isEqualTo(3)
        borrowed.forEach { connection -> verify(exactly = 1) { connection.close() } }
    }

    @Test
    fun `a closed store's leases should refuse an operation, and borrow no connection`(): Unit = runBlocking {
        val store = PostgresStore(StoreConfig.of("lease", "postgres"), source)
        val leases = store.leases
        store.close()
        assertThrows<IllegalStateException> { leases.owners("service") }.message.assert().contains("closed")
        borrowed.assert().isEmpty()
    }

    /** A connection whose lease statements find no row, or run [query] when given one. */
    private fun connection(query: (() -> ResultSet)? = null): Connection {
        val rows = mockk<ResultSet>(relaxed = true) { every { next() } returns false }
        val statement = mockk<PreparedStatement>(relaxed = true) {
            every { executeQuery() } answers { query?.invoke() ?: rows }
        }
        return mockk(relaxed = true) {
            every { autoCommit } returns true
            every { prepareStatement(any<String>()) } returns statement
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a close should abort an operation that does not end within its bound, and give its connection back`(): Unit =
        runBlocking {
            val running = CountDownLatch(1)
            val aborted = CountDownLatch(1)
            val hung = connection {
                running.countDown()
                aborted.await()
                throw SQLException("An I/O error occurred while sending to the backend.", "08006")
            }
            every { hung.abort(any()) } answers { aborted.countDown() }
            val source = mockk<DataSource> { every { connection } returns hung }
            val leases = PostgresLeaseStore(source, dispatcher = Dispatchers.IO, closeTimeout = 50.milliseconds)
            val owners = async(Dispatchers.Default) { runCatching { leases.owners("service") } }
            running.await()
            leases.close()
            // Aborted, then given back; the operation fails as on a broken connection, and a later one is refused.
            verifyOrder {
                hung.abort(any())
                hung.close()
            }
            owners.await().exceptionOrNull().assert().isInstanceOf(StorageUnavailableException::class.java)
            assertThrows<IllegalStateException> { leases.owners("service") }.message.assert().contains("closed")
        }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a claim lent its connection after a close stopped waiting should give it back unused, and fail`(): Unit =
        runBlocking {
            val borrowing = CountDownLatch(1)
            val lent = CountDownLatch(1)
            val late = connection()
            val source = mockk<DataSource> {
                every { connection } answers {
                    borrowing.countDown()
                    lent.await()
                    late
                }
            }
            val leases = PostgresLeaseStore(source, dispatcher = Dispatchers.IO, closeTimeout = 50.milliseconds)
            val claim = async(Dispatchers.Default) {
                runCatching { leases.acquire("service", 0, "node-a", 30.seconds) }
            }
            borrowing.await()
            leases.close()
            lent.countDown()
            claim.await().exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("closed")
            // Given back once, and no statement prepared: no lease is written by a node whose stores are closed.
            verify(exactly = 1) { late.close() }
            verify(exactly = 0) { late.prepareStatement(any<String>()) }
        }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a close that stopped waiting should try both the abort and the give-back, never throwing`(): Unit =
        runBlocking {
            val running = CountDownLatch(1)
            val ended = CountDownLatch(1)
            val hung = connection {
                running.countDown()
                ended.await()
                throw SQLException("This connection has been closed.", "08003")
            }
            every { hung.abort(any()) } throws SQLException("abort is not supported", "0A000")
            every { hung.close() } answers {
                ended.countDown()
                throw SQLException("This connection has been closed.", "08003")
            }
            val source = mockk<DataSource> { every { connection } returns hung }
            val leases = PostgresLeaseStore(source, dispatcher = Dispatchers.IO, closeTimeout = 50.milliseconds)
            val owners = async(Dispatchers.Default) { runCatching { leases.owners("service") } }
            running.await()
            leases.close()
            verifyOrder {
                hung.abort(any())
                hung.close()
            }
            owners.await().exceptionOrNull().assert().isInstanceOf(StorageUnavailableException::class.java)
        }

    @Test
    fun `a store whose leases never ran should close without borrowing a connection`() {
        PostgresStore(StoreConfig.of("lease", "postgres"), source).apply { leases }.close()
        borrowed.assert().isEmpty()
    }
}
