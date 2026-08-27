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

package me.ahoo.wow.messaging.propagation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.SimpleCommandMessage
import me.ahoo.wow.command.wait.COMMAND_WAIT_CONTEXT
import me.ahoo.wow.command.wait.COMMAND_WAIT_ENDPOINT
import me.ahoo.wow.command.wait.COMMAND_WAIT_FUNCTION
import me.ahoo.wow.command.wait.COMMAND_WAIT_PROCESSOR
import me.ahoo.wow.command.wait.COMMAND_WAIT_STAGE
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.WAIT_COMMAND_ID
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.TestNamedMessage
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test

class WaitPlanMessagePropagatorTest {

    @Test
    fun `propagate copies extracted command wait plan for command upstream`() {
        val upstream = commandMessage()
        CommandWait.projected(upstream.commandId, "context", "processor", "function")
            .propagate(TestCommandWaitEndpoint, upstream.header)
        val target = DefaultHeader.empty()

        WaitPlanMessagePropagator().propagate(target, upstream)

        target[WAIT_COMMAND_ID].assert().isEqualTo(upstream.commandId)
        target[COMMAND_WAIT_ENDPOINT].assert().isEqualTo("wait-endpoint")
        target[COMMAND_WAIT_STAGE].assert().isEqualTo("PROJECTED")
        target[COMMAND_WAIT_CONTEXT].assert().isEqualTo("context")
        target[COMMAND_WAIT_PROCESSOR].assert().isEqualTo("processor")
        target[COMMAND_WAIT_FUNCTION].assert().isEqualTo("function")
    }

    @Test
    fun `propagate ignores upstream messages without a complete wait plan`() {
        val target = DefaultHeader.empty()

        WaitPlanMessagePropagator().propagate(target, TestNamedMessage())

        target[WAIT_COMMAND_ID].assert().isNull()
        target[COMMAND_WAIT_ENDPOINT].assert().isNull()
    }

    @Test
    fun `propagate ignores extracted stage wait plan when upstream is not a command message`() {
        val upstream = commandMessage()
        CommandWait.sent(upstream.commandId).propagate(TestCommandWaitEndpoint, upstream.header)
        val nonCommand = TestNamedMessage(header = upstream.header)
        val target = DefaultHeader.empty()

        WaitPlanMessagePropagator().propagate(target, nonCommand)

        target[WAIT_COMMAND_ID].assert().isNull()
        target[COMMAND_WAIT_ENDPOINT].assert().isNull()
    }

    private fun commandMessage(): SimpleCommandMessage<WaitCommand> =
        SimpleCommandMessage(
            id = "command-id",
            body = WaitCommand,
            aggregateId = "wow-core-test.messaging_aggregate".toNamedAggregate().aggregateId("aggregate-id"),
        )

    private object TestCommandWaitEndpoint : CommandWaitEndpoint {
        override val endpoint: String = "wait-endpoint"
    }
}

private object WaitCommand
