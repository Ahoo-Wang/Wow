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

package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.time.Duration

class ClickHouseClientOptionsTest {
    @Test
    fun `should align defaults with the inspector client policy`() {
        val options = ClickHouseClientOptions(listOf(ENDPOINT), password = "secret")

        options.username.assert().isEqualTo("default")
        options.connectionPoolEnabled.assert().isTrue()
        options.connectionTimeout.assert().isEqualTo(Duration.ofSeconds(3))
        options.connectionRequestTimeout.assert().isEqualTo(Duration.ofSeconds(10))
        options.socketTimeout.assert().isEqualTo(Duration.ofSeconds(10))
        options.executionTimeout.assert().isEqualTo(Duration.ofSeconds(10))
        options.maxConnections.assert().isEqualTo(10)
        options.maxRetries.assert().isZero()
        options.toString().assert().contains("password=******").doesNotContain("secret")
    }

    @Test
    fun `should allow driver zero semantics for socket and execution timeouts`() {
        ClickHouseClientOptions(
            endpoints = listOf(ENDPOINT),
            socketTimeout = Duration.ZERO,
            executionTimeout = Duration.ZERO,
        )
    }

    @Test
    fun `should reject invalid driver options before creating the client`() {
        val invalidOptions = invalidEndpointOptions() + invalidTimeoutOptions() + invalidLimitOptions()

        invalidOptions.forEach { (createOptions, expectedMessage) ->
            assertThrows<IllegalArgumentException> {
                createOptions()
            }.message.assert().isEqualTo(expectedMessage)
        }
    }

    private fun invalidEndpointOptions(): List<Pair<() -> Any, String>> {
        return listOf(
            { ClickHouseClientOptions(emptyList()) } to "endpoints must not be empty",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(URI.create("jdbc:clickhouse://clickhouse:8123")),
                )
            } to "endpoints[0] must use http or https",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(URI.create("http:///clickhouse")),
                )
            } to "endpoints[0] must contain a host",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(URI.create("http://clickhouse")),
                )
            } to "endpoints[0] must contain an explicit valid port",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(URI.create("http://clickhouse:65536")),
                )
            } to "endpoints[0] must contain an explicit valid port",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(URI.create("http://user@clickhouse:8123")),
                )
            } to "endpoints[0] must not contain user info, query, or fragment",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(URI.create("http://clickhouse:8123?database=bi")),
                )
            } to "endpoints[0] must not contain user info, query, or fragment",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(URI.create("http://clickhouse:8123#primary")),
                )
            } to "endpoints[0] must not contain user info, query, or fragment",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(ENDPOINT, ENDPOINT),
                )
            } to "endpoints must not contain duplicates",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(ENDPOINT),
                    username = " ",
                )
            } to "username must not be blank",
        )
    }

    private fun invalidTimeoutOptions(): List<Pair<() -> Any, String>> {
        return listOf(
            {
                ClickHouseClientOptions(
                    endpoints = listOf(ENDPOINT),
                    connectionTimeout = Duration.ZERO,
                )
            } to "connectionTimeout must be greater than zero",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(ENDPOINT),
                    connectionRequestTimeout = Duration.ofMillis(-1),
                )
            } to "connectionRequestTimeout must be greater than zero",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(ENDPOINT),
                    socketTimeout = Duration.ofMillis(-1),
                )
            } to "socketTimeout must not be negative",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(ENDPOINT),
                    executionTimeout = Duration.ofSeconds(Long.MAX_VALUE),
                )
            } to "executionTimeout is too large",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(ENDPOINT),
                    socketTimeout = Duration.ofMillis(Int.MAX_VALUE.toLong() + 1),
                )
            } to "socketTimeout must not exceed ${Int.MAX_VALUE} milliseconds",
        )
    }

    private fun invalidLimitOptions(): List<Pair<() -> Any, String>> {
        return listOf(
            {
                ClickHouseClientOptions(
                    endpoints = listOf(ENDPOINT),
                    maxConnections = 0,
                )
            } to "maxConnections must be greater than zero",
            {
                ClickHouseClientOptions(
                    endpoints = listOf(ENDPOINT),
                    maxRetries = -1,
                )
            } to "maxRetries must not be negative",
        )
    }

    private companion object {
        val ENDPOINT: URI = URI.create("http://clickhouse:8123")
    }
}
