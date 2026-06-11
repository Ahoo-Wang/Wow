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
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.DefaultAggregateId
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class LocalCommandWaitNotifierContractTest {

    @Test
    fun `notify forwards local signal`() {
        val registrar = RecordingWaitStrategyRegistrar()
        val notifier = LocalCommandWaitNotifier(registrar)
        val signal = signal()

        StepVerifier.create(notifier.notify(TEST_ENDPOINT, signal))
            .verifyComplete()

        registrar.signals.assert().containsExactly(signal)
    }

    @Test
    fun `notify ignores non local signal`() {
        val registrar = RecordingWaitStrategyRegistrar()
        val notifier = LocalCommandWaitNotifier(registrar)

        StepVerifier.create(notifier.notify(TEST_ENDPOINT, signal(waitCommandId = "")))
            .verifyComplete()

        registrar.signals.assert().isEmpty()
    }

    @Test
    fun `notifyAndForget forwards local signal synchronously`() {
        val registrar = RecordingWaitStrategyRegistrar()
        val notifier = LocalCommandWaitNotifier(registrar)
        val signal = signal()

        notifier.notifyAndForget(TEST_ENDPOINT, signal)

        registrar.signals.assert().containsExactly(signal)
    }

    @Test
    fun `notifyAndForget ignores non local signal`() {
        val registrar = RecordingWaitStrategyRegistrar()
        val notifier = LocalCommandWaitNotifier(registrar)

        notifier.notifyAndForget(TEST_ENDPOINT, signal(waitCommandId = ""))

        registrar.signals.assert().isEmpty()
    }

    @Test
    fun `notifyAndForget swallows registrar failures`() {
        val notifier = LocalCommandWaitNotifier(ThrowingWaitStrategyRegistrar())

        notifier.notifyAndForget(TEST_ENDPOINT, signal())
    }

    private fun signal(waitCommandId: String = generateGlobalId()): WaitSignal =
        SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitCommandId,
            commandId = waitCommandId,
            aggregateId = DefaultAggregateId(TestNamedAggregate, "aggregate-id"),
            stage = CommandStage.PROCESSED,
            function = FunctionInfoData(
                functionKind = FunctionKind.EVENT,
                contextName = TestNamedAggregate.contextName,
                processorName = "processor",
                name = "function",
            ),
        )

    private object TestNamedAggregate : NamedAggregate {
        override val contextName: String = "context"
        override val aggregateName: String = "aggregate"
    }

    private class RecordingWaitStrategyRegistrar : WaitStrategyRegistrar {
        val signals: MutableList<WaitSignal> = mutableListOf()

        override fun register(waitStrategy: WaitStrategy): WaitStrategy? = null

        override fun unregister(waitCommandId: String): WaitStrategy? = null

        override fun get(waitCommandId: String): WaitStrategy? = null

        override fun contains(waitCommandId: String): Boolean = false

        override fun next(signal: WaitSignal): Boolean {
            signals += signal
            return true
        }
    }

    private class ThrowingWaitStrategyRegistrar : WaitStrategyRegistrar {
        override fun register(waitStrategy: WaitStrategy): WaitStrategy? = null

        override fun unregister(waitCommandId: String): WaitStrategy? = null

        override fun get(waitCommandId: String): WaitStrategy? = null

        override fun contains(waitCommandId: String): Boolean = false

        override fun next(signal: WaitSignal): Boolean {
            error("boom")
        }
    }

    private companion object {
        const val TEST_ENDPOINT = "test-endpoint"
    }
}
