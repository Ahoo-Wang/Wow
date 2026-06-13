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

package me.ahoo.wow.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.wait.WaitPlan
import me.ahoo.wow.command.wait.WaitStrategy
import org.junit.jupiter.api.Test

class CommandGatewayApiTest {
    @Test
    fun sendAndWaitShouldUseWaitPlan() {
        CommandGateway::class.java.methods.any { method ->
            method.name == "sendAndWait" &&
                method.parameterTypes.contentEquals(arrayOf(CommandMessage::class.java, WaitPlan::class.java))
        }.assert().isTrue()
    }

    @Test
    fun sendAndWaitStreamShouldUseWaitPlan() {
        CommandGateway::class.java.methods.any { method ->
            method.name == "sendAndWaitStream" &&
                method.parameterTypes.contentEquals(arrayOf(CommandMessage::class.java, WaitPlan::class.java))
        }.assert().isTrue()
    }

    @Test
    fun `command gateway does not expose low-level send with wait strategy`() {
        val lowLevelSendMethods = CommandGateway::class.java.methods.filter { method ->
            method.name == "send" &&
                method.parameterTypes.contentEquals(arrayOf(CommandMessage::class.java, WaitStrategy::class.java))
        }

        lowLevelSendMethods.assert().isEmpty()
    }
}
