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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.query.ClickHouseStreamException
import me.ahoo.wow.spi.subscription.FailureDescriber
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.ServiceLoader

/**
 * A ClickHouse server's failure parks with its code, never the server's message, which quotes what it could not take:
 * here a field's name and a row's value, as the server wrote them.
 */
class ClickHouseFailureDescriberTest {
    private val describer = ClickHouseFailureDescriber()

    /** What [failure] is described as; null when it is not. */
    private fun told(failure: Throwable): String? = describer.describe(failure)?.text

    @Test
    fun `the module should register its describer for ServiceLoader, as a node finds it`() {
        ServiceLoader.load(FailureDescriber::class.java).map { it.javaClass.name }.assert()
            .contains(ClickHouseFailureDescriber::class.java.name)
    }

    @Test
    fun `an unknown field should be its code, without the field or the row`() {
        told(ServerException(UNKNOWN_FIELD, REFUSED, HTTP_BAD_REQUEST, QUERY_ID)).assert()
            .isEqualTo("clickhouse server error code 117")
            .doesNotContain("headers", "alice@example.com", QUERY_ID)
    }

    @Test
    fun `a code ClickHouse may add later should be described as its number`() {
        val later = ServerException(LATER_CODE, LATER, HTTP_SERVER_ERROR, QUERY_ID)
        told(later).assert().isEqualTo("clickhouse server error code 99999")
    }

    @Test
    fun `a server's error without a code should be its HTTP status, or the error alone`() {
        told(ServerException(ServerException.CODE_UNKNOWN, "<html>Bad Gateway</html>", HTTP_BAD_GATEWAY, QUERY_ID))
            .assert().isEqualTo("clickhouse server error http status 502")
        told(ServerException(ServerException.CODE_UNKNOWN, "no answer", 0, QUERY_ID)).assert()
            .isEqualTo("clickhouse server error")
    }

    @Test
    fun `a statement that failed after its first rows should be its code when its text starts with one`() {
        told(ClickHouseStreamException("Code: 159. DB::Exception: Timeout exceeded: alice@example.com")).assert()
            .isEqualTo("clickhouse server error code 159")
        told(ClickHouseStreamException("not a row: alice@example.com")).assert().isEqualTo("clickhouse server error")
    }

    @Test
    fun `a failure that is not the server's should be left to the others`() {
        told(SQLException(REFUSED, "42000", UNKNOWN_FIELD)).assert().isNull()
        told(IllegalStateException(REFUSED)).assert().isNull()
    }

    private companion object {
        const val UNKNOWN_FIELD = 117
        const val LATER_CODE = 99999
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_SERVER_ERROR = 500
        const val HTTP_BAD_GATEWAY = 502
        const val QUERY_ID = "0b8f3c2e-query"
        const val LATER = "Code: 99999. DB::Exception: something new. (SOMETHING_NEW)"
        const val REFUSED = "Code: 117. DB::Exception: Unknown field found while parsing JSONEachRow format: headers: " +
            "(at row 1): While executing ParallelParsingBlockInputFormat. {\"owner\":\"alice@example.com\"}. " +
            "(INCORRECT_DATA) (version 25.3.14.14 (official build))"
    }
}
