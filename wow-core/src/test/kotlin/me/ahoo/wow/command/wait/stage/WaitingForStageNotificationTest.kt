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

package me.ahoo.wow.command.wait.stage

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.TEST_CONTEXT
import me.ahoo.wow.command.wait.testFunction
import me.ahoo.wow.command.wait.testSignal
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class WaitingForStageNotificationTest {

    @Test
    fun `processed strategy emits signal and completes on processed stage`() {
        val strategy = WaitingForStage.processed("wait-id")
        val processed = testSignal(CommandStage.PROCESSED)

        StepVerifier.create(strategy.waiting())
            .then { strategy.next(processed) }
            .expectNext(processed)
            .verifyComplete()
    }

    @Test
    fun `snapshot strategy completes after processed and snapshot and returns merged result`() {
        val strategy = WaitingForStage.snapshot("wait-id")
        val processed = testSignal(CommandStage.PROCESSED, result = mapOf("processed" to 1), signalTime = 1)
        val snapshot = testSignal(CommandStage.SNAPSHOT, result = mapOf("snapshot" to 2), signalTime = 2)

        StepVerifier.create(strategy.waitingLast())
            .then {
                strategy.next(processed)
                strategy.next(snapshot)
            }
            .assertNext { signal ->
                signal.stage.assert().isEqualTo(CommandStage.SNAPSHOT)
                signal.result.assert().isEqualTo(mapOf("processed" to 1, "snapshot" to 2))
            }
            .verifyComplete()
    }

    @Test
    fun `projected strategy waits for processed and last matching projection`() {
        val strategy = WaitingForStage.projected("wait-id", TEST_CONTEXT)
        val processed = testSignal(CommandStage.PROCESSED, signalTime = 1)
        val nonLast = testSignal(
            CommandStage.PROJECTED,
            function = testFunction(contextName = TEST_CONTEXT),
            isLastProjection = false,
            signalTime = 2,
        )
        val last = testSignal(
            CommandStage.PROJECTED,
            function = testFunction(contextName = TEST_CONTEXT),
            isLastProjection = true,
            signalTime = 3,
        )

        StepVerifier.create(strategy.waiting())
            .then {
                strategy.next(processed)
                strategy.next(nonLast)
                strategy.next(last)
            }
            .expectNext(processed, nonLast, last)
            .verifyComplete()
    }

    @Test
    fun `function mismatch keeps function stage waiting`() {
        val strategy = WaitingForStage.eventHandled("wait-id", TEST_CONTEXT)
        val processed = testSignal(CommandStage.PROCESSED)
        val wrongContext = testSignal(
            CommandStage.EVENT_HANDLED,
            function = testFunction(contextName = "other-context"),
        )

        StepVerifier.create(strategy.waiting())
            .then {
                strategy.next(processed)
                strategy.next(wrongContext)
            }
            .expectNext(processed, wrongContext)
            .then {
                strategy.terminated.assert().isFalse()
                strategy.cancelled.assert().isFalse()
                strategy.completed.assert().isFalse()
            }
            .thenCancel()
            .verify()
    }
}
