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

package me.ahoo.wow.command.wait.chain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.COMMAND_WAIT_CONTEXT
import me.ahoo.wow.command.wait.COMMAND_WAIT_ENDPOINT
import me.ahoo.wow.command.wait.COMMAND_WAIT_FUNCTION
import me.ahoo.wow.command.wait.COMMAND_WAIT_PROCESSOR
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.TEST_ENDPOINT
import me.ahoo.wow.command.wait.TestCommandMessage
import me.ahoo.wow.command.wait.TestDomainEvent
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.COMMAND_WAIT_CHAIN
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.SIMPLE_CHAIN
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.COMMAND_WAIT_TAIL_STAGE
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.toWaitingChainTail
import me.ahoo.wow.command.wait.propagateCommandWaitEndpoint
import me.ahoo.wow.command.wait.testNamedFunction
import me.ahoo.wow.messaging.DefaultHeader
import org.junit.jupiter.api.Test

class SimpleWaitingChainPropagationTest {

    @Test
    fun `direct propagate writes simple chain function and tail`() {
        val chain = SimpleWaitingChain(
            function = testNamedFunction(),
            tail = CommandStage.PROJECTED.toWaitingChainTail(testNamedFunction(name = "tail")),
        )
        val header = DefaultHeader.empty()

        chain.propagate(TEST_ENDPOINT, header)

        header[COMMAND_WAIT_CHAIN].assert().isEqualTo(SIMPLE_CHAIN)
        header[COMMAND_WAIT_CONTEXT].assert().isEqualTo(chain.function.contextName)
        header[COMMAND_WAIT_PROCESSOR].assert().isEqualTo(chain.function.processorName)
        header[COMMAND_WAIT_FUNCTION].assert().isEqualTo(chain.function.name)
        header[COMMAND_WAIT_TAIL_STAGE].assert().isEqualTo(CommandStage.PROJECTED.name)
    }

    @Test
    fun `command upstream propagation uses full simple chain`() {
        val chain = SimpleWaitingChain(
            function = testNamedFunction(),
            tail = CommandStage.PROCESSED.toWaitingChainTail(),
        )
        val target = DefaultHeader.empty()
        val upstream = TestCommandMessage(header = DefaultHeader.empty().propagateCommandWaitEndpoint(TEST_ENDPOINT))

        chain.propagate(target, upstream)

        target[COMMAND_WAIT_ENDPOINT].assert().isEqualTo(TEST_ENDPOINT)
        target[COMMAND_WAIT_CHAIN].assert().isEqualTo(SIMPLE_CHAIN)
        target[COMMAND_WAIT_TAIL_STAGE].assert().isEqualTo(CommandStage.PROCESSED.name)
    }

    @Test
    fun `domain event upstream propagation uses tail only`() {
        val chain = SimpleWaitingChain(
            function = testNamedFunction(),
            tail = CommandStage.EVENT_HANDLED.toWaitingChainTail(testNamedFunction(name = "tail")),
        )
        val target = DefaultHeader.empty()
        val upstream = TestDomainEvent(header = DefaultHeader.empty().propagateCommandWaitEndpoint(TEST_ENDPOINT))

        chain.propagate(target, upstream)

        target[COMMAND_WAIT_ENDPOINT].assert().isEqualTo(TEST_ENDPOINT)
        target.containsKey(COMMAND_WAIT_CHAIN).assert().isFalse()
        target[COMMAND_WAIT_TAIL_STAGE].assert().isEqualTo(CommandStage.EVENT_HANDLED.name)
    }
}
