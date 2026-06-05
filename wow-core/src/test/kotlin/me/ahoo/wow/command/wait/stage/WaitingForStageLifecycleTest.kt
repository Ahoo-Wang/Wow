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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.SignalType
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicReference

class WaitingForStageLifecycleTest {

    @Test
    fun `factory creates strategies for every command stage`() {
        WaitingForStage.sent("wait-id").stage.assert().isEqualTo(CommandStage.SENT)
        WaitingForStage.processed("wait-id").stage.assert().isEqualTo(CommandStage.PROCESSED)
        WaitingForStage.snapshot("wait-id").stage.assert().isEqualTo(CommandStage.SNAPSHOT)
        WaitingForStage.projected("wait-id", TEST_CONTEXT).stage.assert().isEqualTo(CommandStage.PROJECTED)
        WaitingForStage.eventHandled("wait-id", TEST_CONTEXT).stage.assert().isEqualTo(CommandStage.EVENT_HANDLED)
        WaitingForStage.sagaHandled("wait-id", TEST_CONTEXT).stage.assert().isEqualTo(CommandStage.SAGA_HANDLED)
        WaitingForStage.stage("wait-id", "processed", TEST_CONTEXT).stage.assert().isEqualTo(CommandStage.PROCESSED)
    }

    @Test
    fun `sent strategy supports void command but processed strategy does not`() {
        WaitingForStage.sent("wait-id").supportVoidCommand.assert().isTrue()
        WaitingForStage.processed("wait-id").supportVoidCommand.assert().isFalse()
    }

    @Test
    fun `on finally hook is invoked when waiting completes`() {
        val strategy = WaitingForStage.processed("wait-id")
        val signalType = AtomicReference<SignalType>()
        strategy.onFinally { signalType.set(it) }

        StepVerifier.create(strategy.waiting())
            .then { strategy.complete() }
            .verifyComplete()

        signalType.get().assert().isEqualTo(SignalType.ON_COMPLETE)
    }

    @Test
    fun `on finally hook can only be set once`() {
        val strategy = WaitingForStage.processed("wait-id")
        strategy.onFinally { }

        assertThrows<IllegalStateException> {
            strategy.onFinally { }
        }
    }
}
