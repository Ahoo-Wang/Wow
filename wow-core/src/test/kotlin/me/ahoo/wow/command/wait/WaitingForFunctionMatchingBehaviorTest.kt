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
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import org.junit.jupiter.api.Test

class WaitingForFunctionMatchingBehaviorTest {

    @Test
    fun `null and empty criteria match any function`() {
        val function = testFunction()

        null.isWaitingForFunction(function).assert().isTrue()
        NamedFunctionInfoData.EMPTY.isWaitingForFunction(function).assert().isTrue()
    }

    @Test
    fun `context criterion uses bounded context comparison`() {
        val function = testFunction(contextName = TEST_CONTEXT)

        testNamedFunction(contextName = TEST_CONTEXT, processorName = "", name = "")
            .isWaitingForFunction(function)
            .assert()
            .isTrue()
        testNamedFunction(contextName = "other-context", processorName = "", name = "")
            .isWaitingForFunction(function)
            .assert()
            .isFalse()
    }

    @Test
    fun `processor and function names must match when specified`() {
        val function = testFunction(processorName = TEST_PROCESSOR, name = TEST_FUNCTION)

        testNamedFunction(contextName = "", processorName = TEST_PROCESSOR, name = "")
            .isWaitingForFunction(function)
            .assert()
            .isTrue()
        testNamedFunction(contextName = "", processorName = "other-processor", name = "")
            .isWaitingForFunction(function)
            .assert()
            .isFalse()
        testNamedFunction(contextName = "", processorName = "", name = TEST_FUNCTION)
            .isWaitingForFunction(function)
            .assert()
            .isTrue()
        testNamedFunction(contextName = "", processorName = "", name = "other-function")
            .isWaitingForFunction(function)
            .assert()
            .isFalse()
    }
}
