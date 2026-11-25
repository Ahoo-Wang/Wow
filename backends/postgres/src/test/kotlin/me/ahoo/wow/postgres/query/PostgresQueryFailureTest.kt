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

package me.ahoo.wow.postgres.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.postgres.PostgresFailures
import me.ahoo.wow.query.error.QueryOutOfRangeException
import me.ahoo.wow.spi.StorageRefusedException
import me.ahoo.wow.spi.StorageTimeoutException
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Duration

/**
 * How a query's statement fails ([PostgresQueryBackend.queryFailure]), against how every other statement does
 * ([PostgresFailures.translate]): a value out of its type's range (SQLSTATE 22003) refuses a query whose computed value
 * went beyond a double's range, and stays the store's refusal of the value for a write.
 */
class PostgresQueryFailureTest {
    private val overflow = SQLException("ERROR: value out of range: overflow", "22003")
    private val underflow = SQLException("ERROR: value out of range: underflow", "22003")

    @Test
    fun `a query's value out of range should be the out-of-range refusal, an underflow too`() {
        listOf(overflow, underflow).forEach { failure ->
            // With a timeout or without: only a cancellation is the timeout's.
            listOf(null, Duration.ofSeconds(1)).forEach { timeout ->
                val refused = PostgresQueryBackend.queryFailure(failure, timeout)
                refused.assert().isInstanceOf(QueryOutOfRangeException::class.java)
                (refused as QueryOutOfRangeException).errorCode.assert().isEqualTo(FrameworkCodes.QUERY_OUT_OF_RANGE)
                refused.cause.assert().isSameAs(failure)
            }
        }
    }

    @Test
    fun `a write's value out of range should stay the store's refusal`() {
        PostgresFailures.translate(overflow).assert().isInstanceOf(StorageRefusedException::class.java)
    }

    @Test
    fun `a query cancelled at its timeout should be the core's timeout, and without one as any statement`() {
        val cancelled = SQLException("ERROR: canceling statement due to statement timeout", "57014")
        PostgresQueryBackend.queryFailure(cancelled, Duration.ofSeconds(1))
            .assert().isInstanceOf(StorageTimeoutException::class.java)
        PostgresQueryBackend.queryFailure(cancelled, timeout = null).assert().isSameAs(cancelled)
    }
}
