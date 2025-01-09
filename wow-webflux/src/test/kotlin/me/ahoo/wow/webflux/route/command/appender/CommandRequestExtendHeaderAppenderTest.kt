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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.util.MultiValueMap
import org.springframework.web.reactive.function.server.ServerRequest

class CommandRequestExtendHeaderAppenderTest {
    @Test
    fun append() {
        val headerKey = "app"
        val key = CommandRequestHeaders.COMMAND_HEADER_X_PREFIX + headerKey
        val value = "oms"
        val request = mockk<ServerRequest> {
            every { headers().asHttpHeaders() } returns HttpHeaders(
                MultiValueMap.fromSingleValue<String, String>(
                    mapOf(
                        key to value
                    )
                )
            )
        }
        val commandHeader = DefaultHeader.empty()
        CommandRequestExtendHeaderAppender.append(request, commandHeader)
        assertThat(commandHeader[headerKey], equalTo(value))
    }

    @Test
    fun appendEmpty() {
        val request = mockk<ServerRequest> {
            every { headers().asHttpHeaders() } returns HttpHeaders()
        }
        val commandHeader = DefaultHeader.empty()
        CommandRequestExtendHeaderAppender.append(request, commandHeader)
        assertThat(commandHeader.isEmpty(), equalTo(true))
    }
}
