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

class SimpleWaitingForChainCancellationBehaviorTest {

    @Test
    fun `cancelling waiting flux marks chain cancelled`() {
        val chain = SimpleWaitingForChain.chain(
            waitCommandId = "main-command",
            function = testNamedFunction(),
            tailStage = CommandStage.PROCESSED,
            tailFunction = testNamedFunction(),
        )

        StepVerifier.create(chain.waiting())
            .thenCancel()
            .verify()

        chain.cancelled.assert().isTrue()
    }

    @Test
    fun `failed signal completes chain because all signals are previous`() {
        val chain = SimpleWaitingForChain.chain(
            waitCommandId = "main-command",
            function = testNamedFunction(),
            tailStage = CommandStage.PROCESSED,
            tailFunction = testNamedFunction(),
        )
        val failed = testSignal(CommandStage.EVENT_HANDLED, commandId = "other-command", errorCode = "FAILED")

        StepVerifier.create(chain.waiting())
            .then { chain.next(failed) }
            .expectNext(failed)
            .verifyComplete()

        chain.terminated.assert().isTrue()
    }
}
