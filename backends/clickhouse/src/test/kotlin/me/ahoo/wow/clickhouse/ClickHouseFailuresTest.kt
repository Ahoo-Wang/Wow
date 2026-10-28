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

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.ConnectionInitiationException
import com.clickhouse.client.api.ServerException
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.clickhouse.query.ClickHouseExecutor
import me.ahoo.wow.clickhouse.sink.ClientStatements
import me.ahoo.wow.spi.StorageTimeoutException
import me.ahoo.wow.spi.StorageUnavailableException
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration

/** ClickHouse unreachable or overloaded is a storage unavailable; a statement's own timeout is its timeout. */
class ClickHouseFailuresTest {
    private fun server(code: Int) = ServerException(code, "Code: $code. DB::Exception", 500, "q-1")

    @Test
    fun `a ClickHouse that cannot be reached is unavailable`() {
        listOf(
            ConnectionInitiationException("Failed to connect", ConnectException("refused")),
            ConnectException("Server returned 503"),
            SocketTimeoutException("Read timed out"),
            UnknownHostException("clickhouse"),
            IllegalStateException("The insert failed.", ConnectException("refused")),
        ).forEach { failure ->
            ClickHouseFailures.translate(failure).assert().isInstanceOf(StorageUnavailableException::class.java)
                .hasCause(failure)
        }
    }

    @Test
    fun `an overloaded server is unavailable, a failed statement stays the client's`() {
        ClickHouseFailures.UNAVAILABLE_CODES.forEach {
            ClickHouseFailures.translate(server(it)).assert().isInstanceOf(StorageUnavailableException::class.java)
        }
        val notFound = server(ServerException.TABLE_NOT_FOUND)
        ClickHouseFailures.translate(notFound).assert().isSameAs(notFound)
        val broken = IllegalStateException("broken")
        ClickHouseFailures.translate(broken).assert().isSameAs(broken)
    }

    @Test
    fun `a statement past its own max_execution_time is its timeout, not the store's`() {
        val timeout = Duration.ofSeconds(2)
        val exceeded = server(ClickHouseExecutor.TIMEOUT_EXCEEDED)
        ClickHouseFailures.translate(exceeded).assert().isSameAs(exceeded)
        ClickHouseExecutor.translate(exceeded, timeout).assert().isInstanceOf(StorageTimeoutException::class.java)
        (ClickHouseExecutor.translate(exceeded, timeout) as StorageTimeoutException).timeout.assert().isEqualTo(timeout)
        ClickHouseExecutor.translate(exceeded, null).assert().isSameAs(exceeded)
        ClickHouseExecutor.translate(
            server(203),
            timeout
        ).assert().isInstanceOf(StorageUnavailableException::class.java)
    }

    @Test
    fun `the Sink's statements to an unreachable server fail as unavailable`() {
        // Nothing listens on port 1 of this host: every request is refused.
        Client.Builder().addEndpoint("http://127.0.0.1:1").setUsername("default").setPassword("").build().use {
            val statements = ClientStatements(it)
            assertThrownBy<StorageUnavailableException> { runBlocking { statements.execute("SELECT 1") } }
            assertThrownBy<StorageUnavailableException> { runBlocking { statements.exists("db", "t") } }
        }
    }
}
