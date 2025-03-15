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
import me.ahoo.wow.id.generateGlobalId
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

internal class LocalCommandWaitNotifierTest {
    @Test
    fun notifyLocal() {
        val commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        commandWaitNotifier.notify(
            "endpoint",
            SimpleWaitSignal(
                id = generateGlobalId(),
                commandId = generateGlobalId(),
                stage = CommandStage.SENT,
                function = COMMAND_GATEWAY_FUNCTION
            ),
        )
            .test()
            .verifyComplete()
    }

    @Test
    fun notifyAndForget() {
        val commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        commandWaitNotifier.notifyAndForget(
            "endpoint",
            SimpleWaitSignal(
                id = generateGlobalId(),
                commandId = generateGlobalId(),
                stage = CommandStage.SENT,
                function = COMMAND_GATEWAY_FUNCTION
            ),
        )
    }

    @Test
    fun notifyRemote() {
        val commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        commandWaitNotifier.notify(
            "endpoint",
            SimpleWaitSignal(
                id = generateGlobalId(),
                commandId = "0THbs0sW0066001",
                stage = CommandStage.SENT,
                function = COMMAND_GATEWAY_FUNCTION
            )
        )
            .test()
            .verifyComplete()
    }
}
