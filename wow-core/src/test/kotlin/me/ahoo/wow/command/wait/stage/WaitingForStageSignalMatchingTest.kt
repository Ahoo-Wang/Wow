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
import me.ahoo.wow.command.wait.testNamedFunction
import me.ahoo.wow.command.wait.testSignal
import org.junit.jupiter.api.Test

class WaitingForStageSignalMatchingTest {

    @Test
    fun `previous signal matching delegates to command stage dependencies`() {
        val snapshot = WaitingForStage.snapshot("wait-id")

        snapshot.isPreviousSignal(testSignal(CommandStage.SENT)).assert().isTrue()
        snapshot.isPreviousSignal(testSignal(CommandStage.PROCESSED)).assert().isTrue()
        snapshot.isPreviousSignal(testSignal(CommandStage.PROJECTED)).assert().isFalse()
    }

    @Test
    fun `projected waits only for last matching projection signal`() {
        val projected = WaitingForProjected("wait-id", testNamedFunction(contextName = TEST_CONTEXT))
        val nonLast = testSignal(
            CommandStage.PROJECTED,
            function = me.ahoo.wow.command.wait.testFunction(contextName = TEST_CONTEXT),
            isLastProjection = false,
        )
        val wrongContext = testSignal(
            CommandStage.PROJECTED,
            function = me.ahoo.wow.command.wait.testFunction(contextName = "other-context"),
            isLastProjection = true,
        )
        val matchingLast = testSignal(
            CommandStage.PROJECTED,
            function = me.ahoo.wow.command.wait.testFunction(contextName = TEST_CONTEXT),
            isLastProjection = true,
        )

        projected.isWaitingFor(nonLast).assert().isFalse()
        projected.isWaitingFor(wrongContext).assert().isFalse()
        projected.isWaitingFor(matchingLast).assert().isTrue()
    }

    @Test
    fun `event and saga handled stages apply function criteria`() {
        val eventHandled = WaitingForEventHandled("wait-id", testNamedFunction(contextName = TEST_CONTEXT))
        val sagaHandled = WaitingForSagaHandled("wait-id", testNamedFunction(contextName = TEST_CONTEXT))

        eventHandled.isWaitingFor(
            testSignal(
                CommandStage.EVENT_HANDLED,
                function = me.ahoo.wow.command.wait.testFunction(contextName = TEST_CONTEXT),
            ),
        ).assert().isTrue()
        eventHandled.isWaitingFor(
            testSignal(
                CommandStage.EVENT_HANDLED,
                function = me.ahoo.wow.command.wait.testFunction(contextName = "other-context"),
            ),
        ).assert().isFalse()
        sagaHandled.isWaitingFor(
            testSignal(
                CommandStage.SAGA_HANDLED,
                function = me.ahoo.wow.command.wait.testFunction(contextName = TEST_CONTEXT),
            ),
        ).assert().isTrue()
    }
}
