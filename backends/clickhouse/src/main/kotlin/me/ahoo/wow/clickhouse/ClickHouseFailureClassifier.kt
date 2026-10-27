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
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Classifies ClickHouse being unreachable or overloaded as systemic: a connection the
 * client could not open, which it reports as a [ConnectionInitiationException] (refused, timed out, no answer, an
 * unknown host), a socket timeout, an HTTP 502 or 503 (which it turns into a [ConnectException]), and the server's
 * codes for overload and lost replicas. Every query of a dataset, and every row a Sink writes, would fail the
 * same way, so a query answers 503 instead of a broken schema, and a subscription pauses its partition. A statement
 * that ran past its own `max_execution_time` is that statement's timeout, not the store's. Found by
 * [java.util.ServiceLoader] (`META-INF/services`) when this backend is on the class path.
 */
internal class ClickHouseFailureClassifier : FailureClassifier {
    override fun classify(failure: Throwable): FailureClass? = when (failure) {
        is ConnectionInitiationException,
        is ConnectException,
        is NoRouteToHostException,
        is UnknownHostException,
        is SocketTimeoutException,
        -> FailureClass.SYSTEMIC
        is ServerException -> if (failure.code in UNAVAILABLE_CODES) FailureClass.SYSTEMIC else null
        else -> null
    }

    companion object {
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
    }
}
