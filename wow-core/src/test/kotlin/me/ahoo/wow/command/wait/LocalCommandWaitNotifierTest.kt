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
import reactor.test.StepVerifier

class LocalCommandWaitNotifierTest {

    @Test
    fun `notify completes and forwards local signal`() {
        val registrar = RecordingWaitStrategyRegistrar()
        val notifier = LocalCommandWaitNotifier(registrar)
        val signal = testSignal(CommandStage.PROCESSED, waitCommandId = generateGlobalId())

        StepVerifier.create(notifier.notify(TEST_ENDPOINT, signal))
            .verifyComplete()

        registrar.signals.assert().containsExactly(signal)
    }

    @Test
    fun `notify completes without forwarding non local signal`() {
        val registrar = RecordingWaitStrategyRegistrar()
        val notifier = LocalCommandWaitNotifier(registrar)
        val signal = testSignal(CommandStage.PROCESSED, waitCommandId = "")

        StepVerifier.create(notifier.notify(TEST_ENDPOINT, signal))
            .verifyComplete()

        registrar.signals.assert().isEmpty()
    }

    @Test
    fun `cancelling notify before request does not forward signal`() {
        val registrar = RecordingWaitStrategyRegistrar()
        val notifier = LocalCommandWaitNotifier(registrar)
        val signal = testSignal(CommandStage.PROCESSED, waitCommandId = generateGlobalId())

        StepVerifier.create(notifier.notify(TEST_ENDPOINT, signal), 0)
            .thenCancel()
            .verify()

        registrar.signals.assert().isEmpty()
    }
}
