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
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.ServerRequest

class AggregateRequestTest {
    @Test
    fun getCommandStage() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandRequestHeaders.WAIT_STAGE) } returns "SENT"
        }
        assertThat(request.getCommandStage(), equalTo(CommandStage.SENT))
    }

    @Test
    fun getCommandStageIfNull() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandRequestHeaders.WAIT_STAGE) } returns null
        }
        assertThat(request.getCommandStage(), equalTo(CommandStage.PROCESSED))
    }

    @Test
    fun getWaitContext() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandRequestHeaders.WAIT_CONTEXT) } returns "test"
        }
        assertThat(request.getWaitContext(), equalTo("test"))
    }

    @Test
    fun getWaitProcessor() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandRequestHeaders.WAIT_PROCESSOR) } returns "test"
        }
        assertThat(request.getWaitProcessor(), equalTo("test"))
    }
}
