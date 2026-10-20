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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.postgres.ledger.PostgresFailureLedger
import me.ahoo.wow.postgres.query.PostgresSnapshotQueryStorage
import me.ahoo.wow.postgres.query.QueryTestSchemas
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import me.ahoo.wow.postgres.subscription.PostgresLeaseStore
import me.ahoo.wow.postgres.timer.PostgresTimerStore
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.spi.StorageUnavailableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLState
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import javax.sql.DataSource

/** A transient failure of PostgreSQL is the SPI's retryable [StorageUnavailableException]; a defect stays the driver's. */
class PostgresFailuresTest {
    private val cart = AggregateType("shop", "cart")

    @Test
    fun `a connection the server ended or that failed should be transient`() {
        listOf(
            // C.1 F2: a restarting server ends every connection with 57P01.
            SQLException("FATAL: terminating connection due to administrator command", "57P01"),
            SQLException("the database system is shutting down", "57P03"),
            SQLException("too many connections", "53300"),
            PSQLException("An I/O error occurred while sending to the backend.", PSQLState.CONNECTION_FAILURE),
            PSQLException("Connection refused.", PSQLState.CONNECTION_UNABLE_TO_CONNECT),
            // Hikari: no connection handed out within its connection timeout.
            SQLTransientConnectionException("Connection is not available, request timed out after 1000ms."),
            SQLException("wrapped", "XX000", SQLException("terminating connection", "57P01")),
        ).forEach { failure ->
            PostgresFailures.isTransient(failure).assert().describedAs("$failure").isTrue()
            val translated = PostgresFailures.translate(failure)
            translated.assert().isInstanceOf(StorageUnavailableException::class.java)
            translated.cause.assert().isSameAs(failure)
            translated.message.assert().isEqualTo("PostgreSQL is unavailable; retry later.")
        }
    }

    @Test
    fun `a defect should stay the driver's exception`() {
        listOf(
            SQLException("syntax error", "42601"),
            SQLException("duplicate key", "23505"),
            SQLException("no state"),
        ).forEach { failure ->
            PostgresFailures.isTransient(failure).assert().describedAs("$failure").isFalse()
            PostgresFailures.translate(failure).assert().isSameAs(failure)
        }
    }

    @Test
    fun `the request id lookup on a server that ended the connection should throw the retryable error (C1 F2)`() {
        val terminated = PSQLException("FATAL: terminating connection", PSQLState.CONNECTION_FAILURE)
        val log = PostgresEventLog(failing(terminated), "shop", partitions = 1, dispatcher = Dispatchers.IO)
        val thrown = assertThrows<StorageUnavailableException> {
            runBlocking { log.findByRequestId(cart, "tenant", "request-1") }
        }
        thrown.causes().assert().contains(terminated)
    }

    @Test
    fun `a snapshot load on an unreachable server should throw the retryable error`() {
        val refused = PSQLException("Connection refused.", PSQLState.CONNECTION_UNABLE_TO_CONNECT)
        val store = PostgresSnapshotStore(failing(refused), "shop", dispatcher = Dispatchers.IO)
        assertThrows<StorageUnavailableException> {
            runBlocking { store.load(cart.ref("tenant", "cart-1")) }
        }.causes().assert().contains(refused)
    }

    @Test
    fun `a snapshot query on an unreachable server should throw the retryable error`() {
        val refused = PSQLException("Connection refused.", PSQLState.CONNECTION_UNABLE_TO_CONNECT)
        val backend = PostgresSnapshotQueryStorage(failing(refused), "shop", dispatcher = Dispatchers.IO)
            .create(QueryTestSchemas.TYPE).backend
        val count = QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), QueryTestSchemas.SCHEMA)
        assertThrows<StorageUnavailableException> {
            runBlocking { backend.count(count) }
        }.causes().assert().contains(refused)
    }

    @Test
    fun `a defect in a call should be thrown as it is`() {
        val defect = SQLException("syntax error", "42601")
        val log = PostgresEventLog(failing(defect), "shop", partitions = 1, dispatcher = Dispatchers.IO)
        assertThrows<SQLException> {
            runBlocking { log.findByRequestId(cart, "tenant", "request-1") }
        }.causes().assert().contains(defect).noneMatch { it is StorageUnavailableException }
    }

    @Test
    fun `the leases, checkpoints, ledger and timers on an unreachable server should throw the retryable error`() {
        // Review A.5: they ran on withContext, so a connection that failed stayed the driver's exception.
        val refused = PSQLException("Connection refused.", PSQLState.CONNECTION_UNABLE_TO_CONNECT)
        val down = failing(refused)
        val calls: List<suspend () -> Any?> = listOf(
            { PostgresLeaseStore(down, dispatcher = Dispatchers.IO).owners("shop") },
            { PostgresCheckpointStore(down, Dispatchers.IO).load("shop", "projection", 0) },
            { PostgresFailureLedger(down, dispatcher = Dispatchers.IO).parked("projection", cart.ref("t", "c")) },
            { PostgresTimerStore(down, "shop", Dispatchers.IO).find(cart.ref("t", "c"), "remind") },
        )
        calls.forEach { call ->
            assertThrows<StorageUnavailableException> { runBlocking { call() } }.causes().assert().contains(refused)
        }
    }

    @Test
    fun `a value PostgreSQL refuses should be a definite refusal, not a defect or unknown`() {
        // Findings A2-7 and A3-10: a NUL in jsonb (22P05) and an index row too large (54000).
        listOf(
            PSQLException("unsupported Unicode escape sequence", PSQLState.DATA_ERROR),
            SQLException("unsupported Unicode escape sequence", "22P05"),
            SQLException("index row size 3000 exceeds btree version 4 maximum 2704", "54000"),
        ).forEach { failure ->
            PostgresFailures.isRefused(failure).assert().describedAs("$failure").isTrue()
            val translated = PostgresFailures.translate(failure)
            translated.assert().isInstanceOf(IllegalArgumentException::class.java)
            translated.cause.assert().isSameAs(failure)
            translated.message.assert().contains("SQLSTATE ${failure.sqlState}")
        }
        PostgresFailures.isRefused(SQLException("syntax error", "42601")).assert().isFalse()
        PostgresFailures.isRefused(SQLException("no state")).assert().isFalse()
    }

    @Test
    fun `a refusal is given back, any other failure thrown`() {
        val refused = SQLException("unsupported Unicode escape sequence", "22P05")
        PostgresFailures.refusedOrThrow(refused).cause.assert().isSameAs(refused)
        val defect = SQLException("syntax error", "42601")
        val thrown: Throwable = assertThrows<SQLException> { PostgresFailures.refusedOrThrow(defect) }
        thrown.assert().isSameAs(defect)
    }

    private fun failing(failure: SQLException): DataSource = mockk {
        every { connection } throws failure
    }
}

/** This throwable and its causes: a coroutine may rethrow a copy of what was thrown, with it as the cause. */
internal fun Throwable.causes(): List<Throwable> = generateSequence(this) { it.cause }.toList()
