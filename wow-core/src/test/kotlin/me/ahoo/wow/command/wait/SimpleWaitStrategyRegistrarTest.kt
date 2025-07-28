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
import me.ahoo.wow.command.COMMAND_GATEWAY_FUNCTION
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import org.junit.jupiter.api.Test

internal class SimpleWaitStrategyRegistrarTest {

    @Test
    fun register() {
        val registrar = SimpleWaitStrategyRegistrar
        val commandId = GlobalIdGenerator.generateAsString()
        val waitStrategy = WaitingForStage.processed()
        var registerResult = registrar.register(commandId, waitStrategy)
        registerResult.assert().isNull()
        registerResult = registrar.register(commandId, waitStrategy)
        registerResult.assert().isEqualTo(waitStrategy)
    }

    @Test
    fun unregister() {
        val registrar = SimpleWaitStrategyRegistrar
        val commandId = GlobalIdGenerator.generateAsString()
        val waitStrategy = WaitingForStage.processed()
        var registerResult = registrar.unregister(commandId)
        registerResult.assert().isNull()
        registerResult = registrar.register(commandId, waitStrategy)
        registerResult.assert().isNull()
        val unregisterResult = registrar.unregister(commandId)
        unregisterResult.assert().isEqualTo(waitStrategy)
    }

    @Test
    fun contains() {
        val registrar = SimpleWaitStrategyRegistrar
        val commandId = GlobalIdGenerator.generateAsString()
        var containsResult = registrar.contains(commandId)
        containsResult.assert().isFalse()
        val waitStrategy = WaitingForStage.processed()
        registrar.register(commandId, waitStrategy)
        containsResult = registrar.contains(commandId)
        containsResult.assert().isTrue()
    }

    @Test
    fun next() {
        val registrar = SimpleWaitStrategyRegistrar
        val commandId = generateGlobalId()

        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            commandId = commandId,
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION
        )
        var nextResult = registrar.next(waitSignal)
        nextResult.assert().isFalse()
        val waitStrategy = WaitingForStage.processed()
        waitStrategy.waiting().subscribe()
        registrar.register(commandId, waitStrategy)
        nextResult = registrar.next(waitSignal)
        nextResult.assert().isTrue()
        registrar.unregister(commandId)
        val containsResult = registrar.contains(commandId)
        containsResult.assert().isFalse()
    }
}
