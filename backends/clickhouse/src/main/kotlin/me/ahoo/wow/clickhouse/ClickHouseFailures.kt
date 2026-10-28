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

package me.ahoo.wow.clickhouse

import com.clickhouse.client.api.ConnectionInitiationException
import com.clickhouse.client.api.ServerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import me.ahoo.wow.clickhouse.query.ClickHouseStreamException
import me.ahoo.wow.spi.StorageBusyException
import me.ahoo.wow.spi.StorageUnavailableException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Tells ClickHouse being unreachable or overloaded from a defect: the first is a [StorageUnavailableException], so a
 * query answers 503 instead of a broken schema and a subscription pauses its partition, the second stays the client's
 * exception.
 *
 * Unavailable are a connection the client could not open, which it reports as a [ConnectionInitiationException]
 * (refused, timed out, no answer, an unknown host), a socket timeout, an HTTP 502 or 503 (which it turns into a
 * [ConnectException]), and the server's codes for overload and lost replicas ([UNAVAILABLE_CODES]); of those, a
 * server at its capacity ([BUSY_CODES]) is a [StorageBusyException]. A statement
 * that ran past its own `max_execution_time` is that statement's timeout, not the store's.
 */
internal object ClickHouseFailures {
    /** The server codes of a ClickHouse that cannot take the statement now, whatever the statement. */
    val UNAVAILABLE_CODES: Set<Int> = setOf(
        202, // TOO_MANY_SIMULTANEOUS_QUERIES
        203, // NO_FREE_CONNECTION
        209, // SOCKET_TIMEOUT
        210, // NETWORK_ERROR
        241, // MEMORY_LIMIT_EXCEEDED
        242, // TABLE_IS_READ_ONLY
        252, // TOO_MANY_PARTS
        285, // TOO_FEW_LIVE_REPLICAS
        999, // KEEPER_EXCEPTION
    )

    /** The server codes of a ClickHouse at its capacity: too many queries at once, no connection free. */
    val BUSY_CODES: Set<Int> = setOf(202, 203)

    private const val MAX_CAUSES = 8

    /** The server's error code of [failure] or the first of its causes that carries one; null when none does. */
    fun codeOf(failure: Throwable): Int? = causes(failure).firstNotNullOfOrNull {
        when (it) {
            is ServerException -> it.code
            is ClickHouseStreamException -> it.code
            else -> null
        }
    }

    /** Whether [failure], or one of its causes, says ClickHouse cannot be reached or cannot take a statement now. */
    fun isUnavailable(failure: Throwable): Boolean = causes(failure).any {
        it is ConnectionInitiationException ||
            it is ConnectException ||
            it is NoRouteToHostException ||
            it is UnknownHostException ||
            it is SocketTimeoutException
    } || codeOf(failure) in UNAVAILABLE_CODES

    /**
     * [failure] as a [StorageBusyException] when ClickHouse is at its capacity ([BUSY_CODES]), a
     * [StorageUnavailableException] when it [isUnavailable], else as it is.
     */
    fun translate(failure: Exception): Exception = when {
        codeOf(failure) in BUSY_CODES -> StorageBusyException("ClickHouse is at its capacity; retry later.", failure)
        isUnavailable(failure) -> StorageUnavailableException("ClickHouse is unavailable; retry later.", failure)
        else -> failure
    }

    private fun causes(failure: Throwable): Sequence<Throwable> =
        generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSES)
}

/** Runs [block] on this dispatcher, ClickHouse being unavailable thrown as a [StorageUnavailableException]. */
@Suppress("TooGenericExceptionCaught") // Any failure of the client is translated, then rethrown.
internal suspend fun <T> CoroutineDispatcher.clickhouse(block: suspend CoroutineScope.() -> T): T = withContext(this) {
    try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        throw ClickHouseFailures.translate(failure)
    }
}
