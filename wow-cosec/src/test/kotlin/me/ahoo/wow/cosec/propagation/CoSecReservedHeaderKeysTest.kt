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

package me.ahoo.wow.cosec.propagation

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.webflux.route.command.appender.CommandRequestExtendHeaderAppender
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

/**
 * Every key [CoSecMessagePropagator] writes is one a `Command-Header-*` request header cannot set: the app id is
 * view-store's isolation key, and the appender order against CoSec's own appender is not fixed. The written keys are
 * observed, not listed, so a key CoSec adds later fails here until wow-webflux reserves it.
 */
class CoSecReservedHeaderKeysTest {

    /** An upstream header that answers every key, so the propagator writes each key it propagates. */
    private class AnswerEveryKeyHeader(private val delegate: Header = DefaultHeader.empty()) : Header by delegate {
        override fun get(key: String): String = "upstream-$key"
    }

    private fun keysCoSecWrites(): Set<String> {
        val upstream = mockk<Message<*, *>> {
            every { header } returns AnswerEveryKeyHeader()
        }
        val written = DefaultHeader.empty()
        CoSecMessagePropagator().propagate(written, upstream)
        return written.keys
    }

    @Test
    fun `every key CoSec propagates is reserved from Command-Header`() {
        val keys = keysCoSecWrites()
        keys.assert().isNotEmpty()

        keys.forEach { key ->
            val request = MockServerRequest.builder()
                .header(CommandHeaders.COMMAND_HEADER_X_PREFIX + key, "forged")
                .build()
            val commandHeader = DefaultHeader.empty()
            assertThrownBy<IllegalArgumentException> {
                CommandRequestExtendHeaderAppender.append(request, commandHeader)
            }.hasMessageContaining("[$key]")
            commandHeader.assert().isEmpty()
        }
    }
}
