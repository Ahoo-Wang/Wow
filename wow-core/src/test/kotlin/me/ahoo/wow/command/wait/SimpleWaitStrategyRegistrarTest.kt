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

import me.ahoo.wow.command.COMMAND_GATEWAY_FUNCTION
import me.ahoo.wow.id.GlobalIdGenerator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class SimpleWaitStrategyRegistrarTest {
    private val contextName = "SimpleWaitStrategyRegistrarTest"

    @Test
    fun register() {
        val registrar = SimpleWaitStrategyRegistrar
        val commandId = GlobalIdGenerator.generateAsString()
        val waitStrategy = WaitingFor.processed(contextName)
        var registerResult = registrar.register(commandId, waitStrategy)
        assertThat(registerResult, nullValue())
        registerResult = registrar.register(commandId, waitStrategy)
        assertThat(registerResult, equalTo(waitStrategy))
    }

    @Test
    fun unregister() {
        val registrar = SimpleWaitStrategyRegistrar
        val commandId = GlobalIdGenerator.generateAsString()
        val waitStrategy = WaitingFor.processed(contextName)
        var registerResult = registrar.unregister(commandId)
        assertThat(registerResult, nullValue())
        registerResult = registrar.register(commandId, waitStrategy)
        assertThat(registerResult, nullValue())
        val unregisterResult = registrar.unregister(commandId)
        assertThat(unregisterResult, equalTo(waitStrategy))
    }

    @Test
    fun contains() {
        val registrar = SimpleWaitStrategyRegistrar
        val commandId = GlobalIdGenerator.generateAsString()
        var containsResult = registrar.contains(commandId)
        assertThat(containsResult, equalTo(false))
        val waitStrategy = WaitingFor.processed(contextName)
        registrar.register(commandId, waitStrategy)
        containsResult = registrar.contains(commandId)
        assertThat(containsResult, equalTo(true))
    }

    @Test
    fun next() {
        val registrar = SimpleWaitStrategyRegistrar
        val commandId = GlobalIdGenerator.generateAsString()

        val waitSignal = SimpleWaitSignal(commandId, CommandStage.PROCESSED, COMMAND_GATEWAY_FUNCTION)
        var nextResult = registrar.next(waitSignal)
        assertThat(nextResult, equalTo(false))
        val waitStrategy = WaitingFor.processed(contextName)
        registrar.register(commandId, waitStrategy)
        nextResult = registrar.next(waitSignal)
        assertThat(nextResult, equalTo(true))
        val containsResult = registrar.contains(commandId)
        assertThat(containsResult, equalTo(false))
    }
}
