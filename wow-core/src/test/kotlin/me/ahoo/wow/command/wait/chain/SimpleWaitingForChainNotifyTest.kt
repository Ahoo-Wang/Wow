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
import me.ahoo.wow.command.wait.testNamedFunction
import me.ahoo.wow.command.wait.testSignal
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class SimpleWaitingForChainNotifyTest {

    @Test
    fun `chain completes after main saga and all tail commands complete`() {
        val chain = SimpleWaitingForChain.chain(
            waitCommandId = "main-command",
            function = testNamedFunction(),
            tailStage = CommandStage.PROCESSED,
            tailFunction = testNamedFunction(),
        )
        val processed = testSignal(CommandStage.PROCESSED, commandId = "main-command", signalTime = 1)
        val saga = testSignal(
            CommandStage.SAGA_HANDLED,
            commandId = "main-command",
            signalTime = 2,
            commands = listOf("tail-a", "tail-b"),
        )
        val tailA = testSignal(CommandStage.PROCESSED, commandId = "tail-a", signalTime = 3)
        val tailB = testSignal(CommandStage.PROCESSED, commandId = "tail-b", signalTime = 4)

        StepVerifier.create(chain.waiting())
            .then {
                chain.next(processed)
                chain.next(saga)
                chain.next(tailA)
                chain.next(tailB)
            }
            .expectNext(processed, saga, tailA, tailB)
            .verifyComplete()

        chain.completed.assert().isTrue()
    }

    @Test
    fun `chain completes immediately after matching saga when no tail commands exist`() {
        val chain = SimpleWaitingForChain.chain(
            waitCommandId = "main-command",
            function = testNamedFunction(),
            tailStage = CommandStage.PROCESSED,
            tailFunction = testNamedFunction(),
        )
        val processed = testSignal(CommandStage.PROCESSED, commandId = "main-command", signalTime = 1)
        val saga = testSignal(CommandStage.SAGA_HANDLED, commandId = "main-command", signalTime = 2)

        StepVerifier.create(chain.waitingLast())
            .then {
                chain.next(processed)
                chain.next(saga)
            }
            .assertNext { signal ->
                signal.stage.assert().isEqualTo(CommandStage.SAGA_HANDLED)
            }
            .verifyComplete()
    }
}
