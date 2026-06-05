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
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.SIMPLE_CHAIN
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.extractSimpleWaitingChain
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.extractWaitChain
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.propagateWaitChain
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.toWaitingChainTail
import me.ahoo.wow.command.wait.testNamedFunction
import me.ahoo.wow.messaging.DefaultHeader
import org.junit.jupiter.api.Test

class SimpleWaitingChainHeaderExtractionTest {

    @Test
    fun `wait chain header extracts raw chain marker`() {
        val header = DefaultHeader.empty().propagateWaitChain(SIMPLE_CHAIN)

        header.extractWaitChain().assert().isEqualTo(SIMPLE_CHAIN)
    }

    @Test
    fun `non simple chain falls back to tail extraction`() {
        val header = DefaultHeader.empty()
        CommandStage.PROCESSED.toWaitingChainTail().propagate("endpoint", header)

        val extracted = header.extractSimpleWaitingChain()

        extracted.assert().isNotNull()
        extracted!!.stage.assert().isEqualTo(CommandStage.PROCESSED)
    }

    @Test
    fun `simple chain without tail extracts null`() {
        val header = DefaultHeader.empty().propagateWaitChain(SIMPLE_CHAIN)

        header.extractSimpleWaitingChain().assert().isNull()
    }

    @Test
    fun `simple chain extracts main function and tail`() {
        val header = DefaultHeader.empty().propagateWaitChain(SIMPLE_CHAIN)
        val function = testNamedFunction()
        val tail = CommandStage.PROJECTED.toWaitingChainTail(testNamedFunction(name = "tail"))
        header
            .with(me.ahoo.wow.command.wait.COMMAND_WAIT_CONTEXT, function.contextName)
            .with(me.ahoo.wow.command.wait.COMMAND_WAIT_PROCESSOR, function.processorName)
            .with(me.ahoo.wow.command.wait.COMMAND_WAIT_FUNCTION, function.name)
        tail.propagate("endpoint", header)

        val extracted = header.extractSimpleWaitingChain() as SimpleWaitingChain

        extracted.function.assert().isEqualTo(function)
        extracted.tail.stage.assert().isEqualTo(CommandStage.PROJECTED)
        extracted.tail.function.name.assert().isEqualTo("tail")
    }
}
