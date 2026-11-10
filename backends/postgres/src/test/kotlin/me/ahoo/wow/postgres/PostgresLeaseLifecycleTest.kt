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
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.store.StoreConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

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

    @Test
    fun `a store whose leases never ran should close without borrowing a connection`() {
        PostgresStore(StoreConfig.of("lease", "postgres"), source).apply { leases }.close()
        borrowed.assert().isEmpty()
    }
}
