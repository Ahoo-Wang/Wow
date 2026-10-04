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

package me.ahoo.wow.webflux.route.command.appender

import me.ahoo.test.asserts.assert
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.remoteIp
import me.ahoo.wow.webflux.route.command.appender.CommandRequestRemoteIpHeaderAppender.X_FORWARDED_FOR
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import java.net.InetAddress
import java.net.InetSocketAddress

class CommandRequestRemoteIpHeaderAppenderTest {

    @Test
    fun `should append remote ip from request`() {
        val hostName = "test"
        val request = MockServerRequest.builder()
            .remoteAddress(InetSocketAddress(hostName, 8080))
            .build()

        val commandHeader = DefaultHeader.empty()
        CommandRequestRemoteIpHeaderAppender.append(request, commandHeader)

        commandHeader.remoteIp.assert().isEqualTo(hostName)
    }

    @Test
    fun `should append remote ip from forwarded for header`() {
        val hostName = "test"
        val request = MockServerRequest.builder().header(X_FORWARDED_FOR, hostName).build()
        val commandHeader = DefaultHeader.empty()
        CommandRequestRemoteIpHeaderAppender.append(request, commandHeader)

        commandHeader.remoteIp.assert().isEqualTo(hostName)
    }

    @Test
    fun `should fallback to remote address when forwarded for is empty`() {
        val hostName = "test"
        val request = MockServerRequest.builder()
            .header(X_FORWARDED_FOR, ",")
            .remoteAddress(InetSocketAddress(hostName, 8080))
            .build()
        val commandHeader = DefaultHeader.empty()
        CommandRequestRemoteIpHeaderAppender.append(request, commandHeader)

        commandHeader.remoteIp.assert().isEqualTo(hostName)
    }

    @Test
    fun `should append the address literal without reverse lookup`() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val request = MockServerRequest.builder()
            .remoteAddress(InetSocketAddress(loopback, 8080))
            .build()
        val commandHeader = DefaultHeader.empty()
        CommandRequestRemoteIpHeaderAppender.append(request, commandHeader)

        commandHeader.remoteIp.assert().isEqualTo("127.0.0.1")
    }

    @Test
    fun `should append the ipv6 address literal without reverse lookup`() {
        val loopback = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
        val request = MockServerRequest.builder()
            .remoteAddress(InetSocketAddress(loopback, 8080))
            .build()
        val commandHeader = DefaultHeader.empty()
        CommandRequestRemoteIpHeaderAppender.append(request, commandHeader)

        commandHeader.remoteIp.assert().isEqualTo(loopback.hostAddress)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "203.0.113.1|203.0.113.1",
            "203.0.113.1, 198.51.100.2|203.0.113.1",
            "203.0.113.1,198.51.100.2,192.0.2.3|203.0.113.1",
            "',203.0.113.1, 198.51.100.2'|203.0.113.1",
            "' , 203.0.113.1'|' 203.0.113.1'",
            "' 203.0.113.1 ,198.51.100.2'|' 203.0.113.1 '",
        ],
        ignoreLeadingAndTrailingWhitespace = false,
    )
    fun `should take the first non blank forwarded for entry as is`(forwardedFor: String, expected: String) {
        val request = MockServerRequest.builder()
            .header(X_FORWARDED_FOR, forwardedFor)
            .remoteAddress(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 8080))
            .build()
        val commandHeader = DefaultHeader.empty()
        CommandRequestRemoteIpHeaderAppender.append(request, commandHeader)

        commandHeader.remoteIp.assert().isEqualTo(expected)
    }

    @Test
    fun `should not append remote ip without forwarded for and remote address`() {
        val commandHeader = DefaultHeader.empty()
        CommandRequestRemoteIpHeaderAppender.append(MockServerRequest.builder().build(), commandHeader)

        commandHeader.remoteIp.assert().isNull()
    }
}
