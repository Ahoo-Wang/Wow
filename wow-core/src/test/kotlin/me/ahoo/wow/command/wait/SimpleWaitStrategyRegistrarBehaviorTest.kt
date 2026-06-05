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
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.id.generateGlobalId
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class SimpleWaitStrategyRegistrarBehaviorTest {

    @Test
    fun `register stores first strategy and returns duplicate`() {
        val waitCommandId = generateGlobalId()
        val first = WaitingForStage.processed(waitCommandId)
        val duplicate = WaitingForStage.sent(waitCommandId)

        try {
            SimpleWaitStrategyRegistrar.register(first).assert().isNull()
            SimpleWaitStrategyRegistrar.register(duplicate).assert().isSameAs(first)
            SimpleWaitStrategyRegistrar.get(waitCommandId).assert().isSameAs(first)
        } finally {
            SimpleWaitStrategyRegistrar.unregister(waitCommandId)
        }
    }

    @Test
    fun `unregister removes strategy and contains reflects registration`() {
        val waitCommandId = generateGlobalId()
        val strategy = WaitingForStage.processed(waitCommandId)

        try {
            SimpleWaitStrategyRegistrar.register(strategy)
            (waitCommandId in SimpleWaitStrategyRegistrar).assert().isTrue()
            SimpleWaitStrategyRegistrar.unregister(waitCommandId).assert().isSameAs(strategy)
            (waitCommandId in SimpleWaitStrategyRegistrar).assert().isFalse()
            SimpleWaitStrategyRegistrar.unregister(waitCommandId).assert().isNull()
        } finally {
            SimpleWaitStrategyRegistrar.unregister(waitCommandId)
        }
    }

    @Test
    fun `next forwards signal to registered strategy`() {
        val waitCommandId = generateGlobalId()
        val strategy = WaitingForStage.processed(waitCommandId)
        val signal = testSignal(CommandStage.PROCESSED, waitCommandId = waitCommandId)

        try {
            SimpleWaitStrategyRegistrar.register(strategy)

            SimpleWaitStrategyRegistrar.next(signal).assert().isTrue()

            StepVerifier.create(strategy.waiting())
                .expectNext(signal)
                .verifyComplete()
        } finally {
            SimpleWaitStrategyRegistrar.unregister(waitCommandId)
        }
    }

    @Test
    fun `next returns false when no strategy is registered`() {
        val signal = testSignal(CommandStage.PROCESSED, waitCommandId = generateGlobalId())

        SimpleWaitStrategyRegistrar.next(signal).assert().isFalse()
    }
}
