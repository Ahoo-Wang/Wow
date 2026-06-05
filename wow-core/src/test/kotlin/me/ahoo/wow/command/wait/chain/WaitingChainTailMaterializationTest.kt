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
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.wait.COMMAND_WAIT_ENDPOINT
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.TEST_ENDPOINT
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.COMMAND_WAIT_TAIL_CONTEXT
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.COMMAND_WAIT_TAIL_STAGE
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.toWaitingChainTail
import me.ahoo.wow.command.wait.testNamedFunction
import me.ahoo.wow.messaging.DefaultHeader
import org.junit.jupiter.api.Test

class WaitingChainTailMaterializationTest {

    @Test
    fun `function stages retain supplied tail function`() {
        val function = testNamedFunction()

        val tail = CommandStage.EVENT_HANDLED.toWaitingChainTail(function)

        tail.stage.assert().isEqualTo(CommandStage.EVENT_HANDLED)
        tail.function.assert().isEqualTo(function)
    }

    @Test
    fun `non function stages materialize with empty tail function`() {
        val tail = CommandStage.SNAPSHOT.toWaitingChainTail(testNamedFunction())

        tail.stage.assert().isEqualTo(CommandStage.SNAPSHOT)
        tail.function.assert().isEqualTo(NamedFunctionInfoData.EMPTY)
    }

    @Test
    fun `propagate writes endpoint stage and non blank function fields`() {
        val tail = CommandStage.PROJECTED.toWaitingChainTail(testNamedFunction(processorName = "", name = ""))
        val header = DefaultHeader.empty()

        tail.propagate(TEST_ENDPOINT, header)

        header[COMMAND_WAIT_ENDPOINT].assert().isEqualTo(TEST_ENDPOINT)
        header[COMMAND_WAIT_TAIL_STAGE].assert().isEqualTo(CommandStage.PROJECTED.name)
        header[COMMAND_WAIT_TAIL_CONTEXT].assert().isEqualTo(tail.function.contextName)
        header.containsKey(COMMAND_WAIT_TAIL_CONTEXT).assert().isTrue()
    }
}
