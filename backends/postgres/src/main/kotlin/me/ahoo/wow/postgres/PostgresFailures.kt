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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import me.ahoo.wow.spi.StorageUnavailableException
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

/**
 * Tells a transient failure of PostgreSQL from a defect: the first is a [StorageUnavailableException], so the runtime
 * answers it as a retryable error, the second stays the driver's exception.
 *
 * Transient are the JDBC transient and recoverable exceptions (a pool that timed out handing out a connection
 * included) and the SQL states of a connection that failed or that the server ended:
 *
 * - class `08`, connection exception (refused, reset, closed);
 * - `57P01` admin shutdown, `57P02` crash shutdown, `57P03` cannot connect now (a server restarting);
 * - `53300` too many connections.
 */
internal object PostgresFailures {
    private const val CONNECTION_EXCEPTION_CLASS = "08"
    private val TRANSIENT_STATES = setOf("57P01", "57P02", "57P03", "53300")
    private val REFUSED_CLASSES = listOf("22", "54")

    /** Whether [failure], or an exception it was caused by, is a transient failure of the server or the connection. */
    fun isTransient(failure: SQLException): Boolean =
        generateSequence<Throwable>(failure) { it.cause.takeIf { cause -> cause !== it } }
            .filterIsInstance<SQLException>()
            .any { it.transient }

    private val SQLException.transient: Boolean
        get() = this is SQLTransientException ||
            this is SQLRecoverableException ||
            sqlState?.startsWith(CONNECTION_EXCEPTION_CLASS) == true ||
            sqlState in TRANSIENT_STATES

    /**
     * Whether PostgreSQL refused a value as it is, for good (findings A2-7, A3-10): class `22`, data exception (a
     * `\u0000` its `jsonb` and `text` do not take, `22P05`; a value out of range), and class `54`, program limit
     * exceeded (an index row too large, `54000`). Writing it again fails the same way.
     */
    fun isRefused(failure: SQLException): Boolean =
        failure.sqlState?.let { state -> REFUSED_CLASSES.any { state.startsWith(it) } } == true

    /** [failure], a refusal ([isRefused]), as the [IllegalArgumentException] every backend refuses a value with. */
    fun refusal(failure: SQLException): IllegalArgumentException =
        IllegalArgumentException(
            "PostgreSQL refuses the value (SQLSTATE ${failure.sqlState}): ${failure.message}",
            failure
        )

    /** [failure] as a refusal ([refusal]) when PostgreSQL refused a value ([isRefused]); else [failure] is thrown. */
    fun refusedOrThrow(failure: SQLException): IllegalArgumentException =
        if (isRefused(failure)) refusal(failure) else throw failure

    /**
     * [failure] as a [StorageUnavailableException] when it [isTransient], as an [IllegalArgumentException] when
     * PostgreSQL refused the value ([isRefused]), else as it is.
     */
    fun translate(failure: SQLException): Exception = when {
        isTransient(failure) -> StorageUnavailableException("PostgreSQL is unavailable; retry later.", failure)
        isRefused(failure) -> refusal(failure)
        else -> failure
    }
}

/** Runs [block] on this dispatcher, a transient [SQLException] thrown as a [StorageUnavailableException]. */
internal suspend fun <T> CoroutineDispatcher.jdbc(block: suspend CoroutineScope.() -> T): T = withContext(this) {
    try {
        block()
    } catch (failure: SQLException) {
        throw PostgresFailures.translate(failure)
    }
}
