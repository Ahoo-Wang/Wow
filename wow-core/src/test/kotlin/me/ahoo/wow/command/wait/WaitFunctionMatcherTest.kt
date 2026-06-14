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

class WaitFunctionMatcherTest {
    @Test
    fun matchEmptyCriteria() {
        val function = testFunction()

        null.matchesWaitFunction(function).assert().isTrue()
        NamedFunctionInfoData.EMPTY.matchesWaitFunction(function).assert().isTrue()
    }

    @Test
    fun matchByContext() {
        val function = testFunction(contextName = TEST_CONTEXT)

        testNamedFunction(contextName = TEST_CONTEXT, processorName = "", name = "")
            .matchesWaitFunction(function)
            .assert().isTrue()
        testNamedFunction(contextName = "other", processorName = "", name = "")
            .matchesWaitFunction(function)
            .assert().isFalse()
    }

    @Test
    fun matchByProcessorAndFunction() {
        val function = testFunction(processorName = TEST_PROCESSOR, name = TEST_FUNCTION)

        testNamedFunction(processorName = TEST_PROCESSOR, name = "")
            .matchesWaitFunction(function)
            .assert().isTrue()
        testNamedFunction(processorName = "other", name = "")
            .matchesWaitFunction(function)
            .assert().isFalse()
        testNamedFunction(processorName = TEST_PROCESSOR, name = TEST_FUNCTION)
            .matchesWaitFunction(function)
            .assert().isTrue()
        testNamedFunction(processorName = TEST_PROCESSOR, name = "other")
            .matchesWaitFunction(function)
            .assert().isFalse()
    }
}
