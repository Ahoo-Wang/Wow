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

package me.ahoo.wow.command.wait

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.test.StepVerifier

class WaitCoordinatorTest {
    @Test
    fun dispatchSignalToLastHandleAndUnregister() {
        val coordinator = DefaultWaitCoordinator(DefaultWaitSignalReducer())
        val handle = coordinator.createLast(CommandWait.processed("wait-id"))

        StepVerifier.create(handle.await())
            .then {
                coordinator.signal(testSignal(CommandStage.SENT, waitCommandId = "wait-id"))
                    .assert().isTrue()
                coordinator.signal(testSignal(CommandStage.PROCESSED, waitCommandId = "wait-id"))
                    .assert().isTrue()
            }
            .assertNext { it.stage.assert().isEqualTo(CommandStage.PROCESSED) }
            .verifyComplete()

        coordinator.contains("wait-id").assert().isFalse()
    }

    @Test
    fun dispatchSignalToStreamHandleAndUnregister() {
        val coordinator = DefaultWaitCoordinator(DefaultWaitSignalReducer())
        val handle = coordinator.createStream(CommandWait.processed("wait-id"))

        coordinator.signal(testSignal(CommandStage.SENT, waitCommandId = "wait-id", signalTime = 1))
            .assert().isTrue()
        coordinator.signal(testSignal(CommandStage.PROCESSED, waitCommandId = "wait-id", signalTime = 2))
            .assert().isTrue()

        StepVerifier.create(handle.stream())
            .assertNext { it.stage.assert().isEqualTo(CommandStage.SENT) }
            .assertNext { it.stage.assert().isEqualTo(CommandStage.PROCESSED) }
            .verifyComplete()

        coordinator.contains("wait-id").assert().isFalse()
    }

    @Test
    fun streamQueueLinkSizeConfiguresUnboundedBufferLinkSize() {
        val coordinator = DefaultWaitCoordinator(
            reducer = DefaultWaitSignalReducer(),
            streamQueueLinkSize = 1,
        )
        val handle = coordinator.createStream(CommandWait.processed("wait-id"))

        coordinator.signal(testSignal(CommandStage.SENT, waitCommandId = "wait-id", signalTime = 1))
            .assert().isTrue()
        coordinator.signal(testSignal(CommandStage.PROCESSED, waitCommandId = "wait-id", signalTime = 2))
            .assert().isTrue()

        coordinator.contains("wait-id").assert().isFalse()
        StepVerifier.create(handle.stream())
            .assertNext { it.stage.assert().isEqualTo(CommandStage.SENT) }
            .assertNext { it.stage.assert().isEqualTo(CommandStage.PROCESSED) }
            .verifyComplete()
    }

    @Test
    fun returnFalseWhenNoHandleExists() {
        val coordinator = DefaultWaitCoordinator(DefaultWaitSignalReducer())

        coordinator.signal(testSignal(CommandStage.PROCESSED, waitCommandId = "missing"))
            .assert().isFalse()
    }

    @Test
    fun ignoredSignalReturnsFalseAndKeepsHandleRegistered() {
        val coordinator = DefaultWaitCoordinator(DefaultWaitSignalReducer())
        val handle = coordinator.createLast(CommandWait.processed("wait-id"))

        coordinator.signal(testSignal(CommandStage.PROJECTED, waitCommandId = "wait-id"))
            .assert().isFalse()

        coordinator.contains("wait-id").assert().isTrue()
        handle.cancel()
        coordinator.contains("wait-id").assert().isFalse()
    }

    @Test
    fun duplicateRegistrationFails() {
        val coordinator = DefaultWaitCoordinator(DefaultWaitSignalReducer())

        coordinator.createLast(CommandWait.processed("wait-id"))
        val error = assertThrows<IllegalArgumentException> {
            coordinator.createStream(CommandWait.processed("wait-id"))
        }

        error.message.assert().isEqualTo("Wait handle already registered for waitCommandId[wait-id].")
    }

    @Test
    fun completionThroughLastHandleUnregistersAndAllowsRegistration() {
        val coordinator = DefaultWaitCoordinator(DefaultWaitSignalReducer())
        val handle = coordinator.createLast(CommandWait.processed("wait-id"))

        coordinator.contains("wait-id").assert().isTrue()

        handle.next(testSignal(CommandStage.PROCESSED, waitCommandId = "wait-id"))
            .assert().isTrue()

        coordinator.contains("wait-id").assert().isFalse()

        coordinator.createLast(CommandWait.processed("wait-id"))
        coordinator.contains("wait-id").assert().isTrue()
    }

    @Test
    fun cancellationThroughStreamHandleUnregistersAndAllowsRegistration() {
        val coordinator = DefaultWaitCoordinator(DefaultWaitSignalReducer())
        val handle = coordinator.createStream(CommandWait.processed("wait-id"))

        coordinator.contains("wait-id").assert().isTrue()

        handle.cancel()

        coordinator.contains("wait-id").assert().isFalse()

        coordinator.createStream(CommandWait.processed("wait-id"))
        coordinator.contains("wait-id").assert().isTrue()
    }
}
