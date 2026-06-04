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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosid.cosid.ClockSyncCosIdGenerator
import me.ahoo.cosid.cosid.Radix62CosIdGenerator
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

internal class LocalCommandWaitNotifierTest {
    private val functionInfo = FunctionInfoData(
        functionKind = FunctionKind.COMMAND,
        contextName = "wow",
        processorName = "LocalCommandWaitNotifierTest",
        name = "Send"
    )

    @Test
    fun `should notify local`() {
        val commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        commandWaitNotifier.notify(
            "endpoint",
            SimpleWaitSignal(
                id = generateGlobalId(),
                waitCommandId = generateGlobalId(),
                commandId = generateGlobalId(),
                aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
                stage = CommandStage.SENT,
                function = functionInfo
            ),
        )
            .test()
            .verifyComplete()
    }

    @Test
    fun `should notify and forget`() {
        val commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        commandWaitNotifier.notifyAndForget(
            "endpoint",
            SimpleWaitSignal(
                id = generateGlobalId(),
                waitCommandId = generateGlobalId(),
                commandId = generateGlobalId(),
                aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
                stage = CommandStage.SENT,
                function = functionInfo
            ),
        )
    }

    @Test
    fun `should notify remote`() {
        val commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        commandWaitNotifier.notify(
            "endpoint",
            SimpleWaitSignal(
                id = generateGlobalId(),
                waitCommandId = "0THbs0sW0066001",
                commandId = "0THbs0sW0066001",
                aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
                stage = CommandStage.SENT,
                function = functionInfo
            )
        )
            .test()
            .verifyComplete()
    }

    @Test
    fun `should notify local wait strategy when signal id is remote`() {
        val registrar = mockk<WaitStrategyRegistrar> {
            every { next(any()) } returns true
        }
        val commandWaitNotifier = LocalCommandWaitNotifier(registrar)
        val waitCommandId = generateGlobalId()
        val remoteSignalId = ClockSyncCosIdGenerator(
            Radix62CosIdGenerator(remoteMachineId())
        ).generateAsString()
        val waitSignal = SimpleWaitSignal(
            id = remoteSignalId,
            waitCommandId = waitCommandId,
            commandId = remoteSignalId,
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROCESSED,
            function = functionInfo
        )

        commandWaitNotifier.notify("endpoint", waitSignal)
            .test()
            .verifyComplete()

        verify(exactly = 1) {
            registrar.next(waitSignal)
        }
    }

    private fun remoteMachineId(): Int {
        val localMachineId = GlobalIdGenerator.machineId
        return if (localMachineId == 1) {
            2
        } else {
            1
        }
    }
}
