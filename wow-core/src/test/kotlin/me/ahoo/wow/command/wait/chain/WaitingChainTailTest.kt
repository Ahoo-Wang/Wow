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
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.extractWaitingChainTail
import me.ahoo.wow.messaging.DefaultHeader
import org.junit.jupiter.api.Test

class WaitingChainTailTest {

    @Test
    fun propagate() {
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val header = DefaultHeader.empty()
        tail.propagate("endpoint", header)
        val extracted = header.extractWaitingChainTail()
        extracted.assert().isNotNull()
        extracted!!.function.assert().isEqualTo(function)
        extracted.stage.assert().isEqualTo(CommandStage.PROCESSED)
    }

    @Test
    fun propagateWithEmptyValues() {
        val function = NamedFunctionInfoData("", "", "")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val header = DefaultHeader.empty()
        tail.propagate("endpoint", header)

        header[WaitingChainTail.COMMAND_WAIT_TAIL_STAGE].assert().isEqualTo(CommandStage.PROCESSED.name)
    }

    @Test
    fun extractWaitingTailNodeWhenStageIsNull() {
        val header = DefaultHeader.empty()
        header.extractWaitingChainTail().assert().isNull()
    }
}
