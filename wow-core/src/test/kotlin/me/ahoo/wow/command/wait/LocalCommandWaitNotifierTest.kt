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
import me.ahoo.wow.id.generateGlobalId
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import reactor.test.StepVerifier

class LocalCommandWaitNotifierTest {

    @Test
    fun `notify completes and forwards local signal`() {
        val coordinator = RecordingWaitCoordinator()
        val notifier = LocalCommandWaitNotifier(coordinator)
        val signal = testSignal(CommandStage.PROCESSED, waitCommandId = generateGlobalId())

        notifier.notify(TEST_ENDPOINT, signal)
            .test()
            .verifyComplete()

        coordinator.signals.assert().containsExactly(signal)
    }

    @Test
    fun `notify completes without forwarding non local signal`() {
        val coordinator = RecordingWaitCoordinator()
        val notifier = LocalCommandWaitNotifier(coordinator)
        val signal = testSignal(CommandStage.PROCESSED, waitCommandId = "")

        notifier.notify(TEST_ENDPOINT, signal)
            .test()
            .verifyComplete()

        coordinator.signals.assert().isEmpty()
    }

    @Test
    fun `notifyAndForget forwards local signal synchronously`() {
        val coordinator = RecordingWaitCoordinator()
        val notifier = LocalCommandWaitNotifier(coordinator)
        val signal = testSignal(CommandStage.PROCESSED, waitCommandId = generateGlobalId())

        notifier.notifyAndForget(TEST_ENDPOINT, signal)

        coordinator.signals.assert().containsExactly(signal)
    }

    @Test
    fun `notifyAndForget swallows coordinator failures`() {
        val notifier = LocalCommandWaitNotifier(ThrowingWaitCoordinator())
        val signal = testSignal(CommandStage.PROCESSED, waitCommandId = generateGlobalId())

        notifier.notifyAndForget(TEST_ENDPOINT, signal)
    }

    @Test
    fun `cancelling notify before request does not forward signal`() {
        val coordinator = RecordingWaitCoordinator()
        val notifier = LocalCommandWaitNotifier(coordinator)
        val signal = testSignal(CommandStage.PROCESSED, waitCommandId = generateGlobalId())

        StepVerifier.create(notifier.notify(TEST_ENDPOINT, signal), 0)
            .thenCancel()
            .verify()

        coordinator.signals.assert().isEmpty()
    }

    private class RecordingWaitCoordinator : WaitCoordinator {
        val signals: MutableList<WaitSignal> = mutableListOf()

        override fun createLast(plan: WaitPlan): WaitLastHandle =
            error("not used")

        override fun createStream(plan: WaitPlan): WaitStreamHandle =
            error("not used")

        override fun signal(signal: WaitSignal): Boolean {
            signals += signal
            return true
        }

        override fun contains(waitCommandId: String): Boolean = false
    }

    private class ThrowingWaitCoordinator : WaitCoordinator {
        override fun createLast(plan: WaitPlan): WaitLastHandle =
            error("not used")

        override fun createStream(plan: WaitPlan): WaitStreamHandle =
            error("not used")

        override fun signal(signal: WaitSignal): Boolean {
            error("boom")
        }

        override fun contains(waitCommandId: String): Boolean = false
    }
}
