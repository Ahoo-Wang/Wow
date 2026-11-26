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

import com.clickhouse.client.api.ServerException
import me.ahoo.wow.clickhouse.query.ClickHouseStreamException
import me.ahoo.wow.spi.subscription.FailureDescriber
import me.ahoo.wow.spi.subscription.FailureDescription
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.code
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.phrase

/** The words of ClickHouse's descriptions. */
internal enum class ClickHousePhrase {
    CLICKHOUSE_SERVER_ERROR,
    CODE,
    HTTP_STATUS,
}

/**
 * The ClickHouse client's failures, described for the reason a parked aggregate records: a server's error by its code
 * (`clickhouse server error code 117`), which an operator looks up among ClickHouse's error codes and finds in full,
 * the server's message and all, in the node's log line of the park. Never the message, which may quote a row's value
 * (one it could not parse, a key). ClickHouse's own name of the error is part of that message only, not a field of the
 * client's exception, so it is not described either.
 *
 * A [ServerException] without a code ([ServerException.CODE_UNKNOWN], an HTTP error the server's headers did not
 * explain) is described by its HTTP status; a statement that failed after its first rows were sent
 * ([ClickHouseStreamException]) by the code its text starts with, when it does.
 *
 * Found by `ServiceLoader`, from this module's `META-INF/services/me.ahoo.wow.spi.subscription.FailureDescriber`.
 */
internal class ClickHouseFailureDescriber : FailureDescriber {
    override fun describe(failure: Throwable): FailureDescription? = when (failure) {
        is ServerException -> when {
            failure.code != ServerException.CODE_UNKNOWN -> withCode(failure.code)
            failure.transportProtocolCode > 0 -> FailureDescription.of(
                phrase(ClickHousePhrase.CLICKHOUSE_SERVER_ERROR),
                phrase(ClickHousePhrase.HTTP_STATUS),
                code(failure.transportProtocolCode.toLong()),
            )
            else -> FailureDescription.of(phrase(ClickHousePhrase.CLICKHOUSE_SERVER_ERROR))
        }
        is ClickHouseStreamException -> failure.code?.let(::withCode)
            ?: FailureDescription.of(phrase(ClickHousePhrase.CLICKHOUSE_SERVER_ERROR))
        else -> null
    }

    private fun withCode(code: Int): FailureDescription = FailureDescription.of(
        phrase(ClickHousePhrase.CLICKHOUSE_SERVER_ERROR),
        phrase(ClickHousePhrase.CODE),
        code(code.toLong()),
    )
}
