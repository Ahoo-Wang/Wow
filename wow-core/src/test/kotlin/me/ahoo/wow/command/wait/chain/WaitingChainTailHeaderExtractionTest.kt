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
import me.ahoo.wow.command.wait.TEST_CONTEXT
import me.ahoo.wow.command.wait.TEST_FUNCTION
import me.ahoo.wow.command.wait.TEST_PROCESSOR
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.COMMAND_WAIT_TAIL_CONTEXT
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.COMMAND_WAIT_TAIL_FUNCTION
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.COMMAND_WAIT_TAIL_PROCESSOR
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.COMMAND_WAIT_TAIL_STAGE
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.extractWaitingChainTail
import me.ahoo.wow.messaging.DefaultHeader
import org.junit.jupiter.api.Test

class WaitingChainTailHeaderExtractionTest {

    @Test
    fun `missing tail stage extracts null`() {
        DefaultHeader.empty().extractWaitingChainTail().assert().isNull()
    }

    @Test
    fun `tail header extracts stage and function fields`() {
        val header = DefaultHeader.empty()
            .with(COMMAND_WAIT_TAIL_STAGE, CommandStage.PROJECTED.name)
            .with(COMMAND_WAIT_TAIL_CONTEXT, TEST_CONTEXT)
            .with(COMMAND_WAIT_TAIL_PROCESSOR, TEST_PROCESSOR)
            .with(COMMAND_WAIT_TAIL_FUNCTION, TEST_FUNCTION)

        val tail = header.extractWaitingChainTail()!!

        tail.stage.assert().isEqualTo(CommandStage.PROJECTED)
        tail.function.contextName.assert().isEqualTo(TEST_CONTEXT)
        tail.function.processorName.assert().isEqualTo(TEST_PROCESSOR)
        tail.function.name.assert().isEqualTo(TEST_FUNCTION)
    }

    @Test
    fun `missing tail function fields extract as empty strings`() {
        val header = DefaultHeader.empty()
            .with(COMMAND_WAIT_TAIL_STAGE, CommandStage.PROCESSED.name)

        val tail = header.extractWaitingChainTail()!!

        tail.stage.assert().isEqualTo(CommandStage.PROCESSED)
        tail.function.isEmpty().assert().isTrue()
    }
}
