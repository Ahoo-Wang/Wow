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
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.messaging.function.NamedFunctionInfo
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import org.junit.jupiter.api.Test

class WaitingForsTest {

    private val testFunctionInfo = object : FunctionInfo {
        override val functionKind: FunctionKind = FunctionKind.COMMAND
        override val contextName: String = "test-context"
        override val processorName: String = "test-processor"
        override val name: String = "test-function"
    }

    @Test
    fun isWaitingForFunctionWhenNull() {
        val waitingFor: NamedFunctionInfo? = null
        waitingFor.isWaitingForFunction(testFunctionInfo).assert().isTrue()
    }

    @Test
    fun isWaitingForFunctionWithAllEmpty() {
        val waitingFor = NamedFunctionInfoData(
            contextName = "",
            processorName = "",
            name = ""
        )
        waitingFor.isWaitingForFunction(testFunctionInfo).assert().isTrue()
    }

    @Test
    fun isWaitingForFunctionWithOnlyContext() {
        val waitingFor = NamedFunctionInfoData(
            contextName = "test-context",
            processorName = "",
            name = ""
        )
        waitingFor.isWaitingForFunction(testFunctionInfo).assert().isTrue()

        val waitingForDifferentContext = NamedFunctionInfoData(
            contextName = "different-context",
            processorName = "",
            name = ""
        )
        waitingForDifferentContext.isWaitingForFunction(testFunctionInfo).assert().isFalse()
    }

    @Test
    fun isWaitingForFunctionWithOnlyProcessor() {
        val waitingFor = NamedFunctionInfoData(
            contextName = "",
            processorName = "test-processor",
            name = ""
        )
        waitingFor.isWaitingForFunction(testFunctionInfo).assert().isTrue()

        val waitingForDifferentProcessor = NamedFunctionInfoData(
            contextName = "",
            processorName = "different-processor",
            name = ""
        )
        waitingForDifferentProcessor.isWaitingForFunction(testFunctionInfo).assert().isFalse()
    }

    @Test
    fun isWaitingForFunctionWithOnlyName() {
        val waitingFor = NamedFunctionInfoData(
            contextName = "",
            processorName = "",
            name = "test-function"
        )
        waitingFor.isWaitingForFunction(testFunctionInfo).assert().isTrue()

        val waitingForDifferentName = NamedFunctionInfoData(
            contextName = "",
            processorName = "",
            name = "different-function"
        )
        waitingForDifferentName.isWaitingForFunction(testFunctionInfo).assert().isFalse()
    }

    @Test
    fun isWaitingForFunctionWithAllFields() {
        val waitingFor = NamedFunctionInfoData(
            contextName = "test-context",
            processorName = "test-processor",
            name = "test-function"
        )
        waitingFor.isWaitingForFunction(testFunctionInfo).assert().isTrue()

        val waitingForDifferentAll = NamedFunctionInfoData(
            contextName = "different-context",
            processorName = "different-processor",
            name = "different-function"
        )
        waitingForDifferentAll.isWaitingForFunction(testFunctionInfo).assert().isFalse()
    }

    @Test
    fun isWaitingForFunctionWithPartialMatch() {
        val waitingFor = NamedFunctionInfoData(
            contextName = "test-context",
            processorName = "test-processor",
            name = ""
        )
        waitingFor.isWaitingForFunction(testFunctionInfo).assert().isTrue()

        val waitingForContextAndDifferentProcessor = NamedFunctionInfoData(
            contextName = "test-context",
            processorName = "different-processor",
            name = ""
        )
        waitingForContextAndDifferentProcessor.isWaitingForFunction(testFunctionInfo).assert().isFalse()
    }
}
