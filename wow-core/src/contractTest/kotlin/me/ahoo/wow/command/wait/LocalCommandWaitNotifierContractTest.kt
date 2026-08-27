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
import reactor.kotlin.test.test

class LocalCommandWaitNotifierContractTest {

    @Test
    fun `notify forwards local signal`() {
        val coordinator = RecordingWaitCoordinator()
        val notifier = LocalCommandWaitNotifier(coordinator)
        val signal = signal()

        notifier.notify(TEST_ENDPOINT, signal)
            .test()
            .verifyComplete()

        coordinator.signals.assert().containsExactly(signal)
    }

    @Test
    fun `notify ignores non local signal`() {
        val coordinator = RecordingWaitCoordinator()
        val notifier = LocalCommandWaitNotifier(coordinator)

        notifier.notify(TEST_ENDPOINT, signal(waitCommandId = ""))
            .test()
            .verifyComplete()

        coordinator.signals.assert().isEmpty()
    }

    @Test
    fun `notifyAndForget forwards local signal synchronously`() {
        val coordinator = RecordingWaitCoordinator()
        val notifier = LocalCommandWaitNotifier(coordinator)
        val signal = signal()

        notifier.notifyAndForget(TEST_ENDPOINT, signal)

        coordinator.signals.assert().containsExactly(signal)
    }

    @Test
    fun `notifyAndForget ignores non local signal`() {
        val coordinator = RecordingWaitCoordinator()
        val notifier = LocalCommandWaitNotifier(coordinator)

        notifier.notifyAndForget(TEST_ENDPOINT, signal(waitCommandId = ""))

        coordinator.signals.assert().isEmpty()
    }

    @Test
    fun `notifyAndForget swallows coordinator failures`() {
        val notifier = LocalCommandWaitNotifier(ThrowingWaitCoordinator())

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

    private companion object {
        const val TEST_ENDPOINT = "test-endpoint"
    }
}
