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
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import java.net.InetSocketAddress

class CommandRequestRemoteIpHeaderAppenderTest {

    @Test
    fun append() {
        val hostName = "test"
        val request = MockServerRequest.builder()
            .remoteAddress(InetSocketAddress(hostName, 8080))
            .build()

        val commandHeader = DefaultHeader.empty()
        CommandRequestRemoteIpHeaderAppender.append(request, commandHeader)

        commandHeader.remoteIp.assert().isEqualTo(hostName)
    }

    @Test
    fun appendForwardedFor() {
        val hostName = "test"
        val request = MockServerRequest.builder().header(X_FORWARDED_FOR, hostName).build()
        val commandHeader = DefaultHeader.empty()
        CommandRequestRemoteIpHeaderAppender.append(request, commandHeader)

        commandHeader.remoteIp.assert().isEqualTo(hostName)
    }

    @Test
    fun appendEmptyForwardedFor() {
        val hostName = "test"
        val request = MockServerRequest.builder()
            .header(X_FORWARDED_FOR, ",")
            .remoteAddress(InetSocketAddress(hostName, 8080))
            .build()
        val commandHeader = DefaultHeader.empty()
        CommandRequestRemoteIpHeaderAppender.append(request, commandHeader)

        commandHeader.remoteIp.assert().isEqualTo(hostName)
    }
}
