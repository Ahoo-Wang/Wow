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

package me.ahoo.wow.webflux.route.command

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.remoteIp
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.userAgent
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.server.ServerRequest
import java.net.InetSocketAddress
import java.util.*

class CommandRequestUserAgentHeaderAppenderTest {
    @Test
    fun appendUserAgent() {
        val userAgent = "test"
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(HttpHeaders.USER_AGENT) } returns userAgent
            every { remoteAddress() } returns Optional.empty()
        }
        val commandHeader = DefaultHeader.empty()
        CommandRequestUserAgentHeaderAppender.append(request, commandHeader)

        assertThat(commandHeader.userAgent, equalTo(userAgent))
    }

    @Test
    fun appendRemoteIp() {
        val hostName = "test"
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(HttpHeaders.USER_AGENT) } returns null
            every { remoteAddress() } returns Optional.of(InetSocketAddress(hostName, 8080))
        }
        val commandHeader = DefaultHeader.empty()
        CommandRequestUserAgentHeaderAppender.append(request, commandHeader)

        assertThat(commandHeader.remoteIp, equalTo(hostName))
    }
}
