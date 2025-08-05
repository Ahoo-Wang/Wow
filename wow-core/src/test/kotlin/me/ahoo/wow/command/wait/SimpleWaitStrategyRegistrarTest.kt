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
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test

internal class SimpleWaitStrategyRegistrarTest {

    @Test
    fun register() {
        val registrar = SimpleWaitStrategyRegistrar
        val waitStrategy = WaitingForStage.processed(generateGlobalId())
        var registerResult = registrar.register(waitStrategy)
        registerResult.assert().isNull()
        registerResult = registrar.register(waitStrategy)
        registerResult.assert().isEqualTo(waitStrategy)
    }

    @Test
    fun unregister() {
        val registrar = SimpleWaitStrategyRegistrar
        val waitStrategy = WaitingForStage.processed(generateGlobalId())
        var registerResult = registrar.unregister(waitStrategy.waitCommandId)
        registerResult.assert().isNull()
        registerResult = registrar.register(waitStrategy)
        registerResult.assert().isNull()
        val unregisterResult = registrar.unregister(waitStrategy.waitCommandId)
        unregisterResult.assert().isEqualTo(waitStrategy)
    }

    @Test
    fun contains() {
        val registrar = SimpleWaitStrategyRegistrar
        val waitStrategy = WaitingForStage.processed(generateGlobalId())
        var containsResult = registrar.contains(waitStrategy.waitCommandId)
        containsResult.assert().isFalse()
        registrar.register(waitStrategy)
        containsResult = registrar.contains(waitStrategy.waitCommandId)
        containsResult.assert().isTrue()
    }

    @Test
    fun next() {
        val registrar = SimpleWaitStrategyRegistrar
        val waitStrategy = WaitingForStage.processed(generateGlobalId())
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROCESSED,
            function = FunctionInfoData(
                functionKind = FunctionKind.COMMAND,
                contextName = "wow",
                processorName = "SimpleWaitStrategyRegistrarTest",
                name = "Send"
            )
        )
        var nextResult = registrar.next(waitSignal)
        nextResult.assert().isFalse()
        waitStrategy.waiting().subscribe()
        registrar.register(waitStrategy)
        nextResult = registrar.next(waitSignal)
        nextResult.assert().isTrue()
        registrar.unregister(waitStrategy.waitCommandId)
        val containsResult = registrar.contains(waitStrategy.waitCommandId)
        containsResult.assert().isFalse()
    }
}
