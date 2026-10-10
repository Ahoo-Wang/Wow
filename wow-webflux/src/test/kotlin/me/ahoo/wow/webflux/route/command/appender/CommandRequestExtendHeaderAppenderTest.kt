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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.rest.CommandHeaders
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class CommandRequestExtendHeaderAppenderTest {
    @Test
    fun `should append extend headers from request`() {
        val headerKey = "app"
        val key = CommandHeaders.COMMAND_HEADER_X_PREFIX + headerKey
        val value = "oms"

        val request = MockServerRequest.builder()
            .header(key, value)
            .build()
        val commandHeader = DefaultHeader.empty()
        CommandRequestExtendHeaderAppender.append(request, commandHeader)
        commandHeader[headerKey].assert().isEqualTo(value)
    }

    @Test
    fun `should not append when no extend headers present`() {
        val request = MockServerRequest.builder()
            .build()
        val commandHeader = DefaultHeader.empty()
        CommandRequestExtendHeaderAppender.append(request, commandHeader)
        commandHeader.assert().isEmpty()
    }

    @Test
    fun `the prefix matches case-insensitively, as HTTP 2 sends lower-case names`() {
        val request = MockServerRequest.builder()
            .header("command-header-app", "oms")
            .header("COMMAND-HEADER-region", "eu")
            .build()
        val commandHeader = DefaultHeader.empty()
        CommandRequestExtendHeaderAppender.append(request, commandHeader)
        commandHeader["app"].assert().isEqualTo("oms")
        commandHeader["region"].assert().isEqualTo("eu")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Command-Header-command_operator",
            "command-header-command_operator",
            "Command-Header-Command_Operator",
            "Command-Header-local_first",
            "Command-Header-trace_id",
            "Command-Header-upstream_id",
            "Command-Header-upstream_name",
            "Command-Header-user_agent",
            "Command-Header-remote_ip",
            "Command-Header-command_wait_id",
            "Command-Header-command_wait_endpoint",
            "Command-Header-command_wait_stage",
            "Command-Header-command_wait_chain",
            "Command-Header-command_wait_tail_stage",
            "Command-Header-command_wait_anything",
            "Command-Header-compensate.id",
            "Command-Header-compensate.processor",
            "Command-Header-traceparent",
            "Command-Header-app_id",
            "command-header-device_id",
        ]
    )
    fun `a reserved key is rejected and nothing is copied`(name: String) {
        val request = MockServerRequest.builder()
            .header("Command-Header-app", "oms")
            .header(name, "spoofed")
            .build()
        val commandHeader = DefaultHeader.empty()

        assertThrownBy<IllegalArgumentException> {
            CommandRequestExtendHeaderAppender.append(request, commandHeader)
        }.hasMessageContaining("is reserved by the framework")
        commandHeader.assert().isEmpty()
    }

    @Test
    fun `the reserved set is derived from the framework's header writers`() {
        ReservedCommandHeaderKeys.keys.assert().containsExactlyInAnyOrder(
            "command_operator",
            "local_first",
            "trace_id",
            "upstream_id",
            "upstream_name",
            "user_agent",
            "remote_ip",
            "traceparent",
            "tracestate",
            "baggage",
            "app_id",
            "device_id",
        )
        ReservedCommandHeaderKeys.prefixes.assert().containsExactly("command_wait_", "compensate.")
    }
}
