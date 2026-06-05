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

package me.ahoo.wow.messaging.compensation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.TestNamedMessage
import me.ahoo.wow.messaging.compensation.CompensationMatcher.compensationId
import me.ahoo.wow.messaging.compensation.CompensationMatcher.isCompensation
import me.ahoo.wow.messaging.compensation.CompensationMatcher.match
import me.ahoo.wow.messaging.compensation.CompensationMatcher.withCompensation
import org.junit.jupiter.api.Test

class CompensationMatcherBehaviorTest {

    @Test
    fun `withCompensation writes target identity to the header`() {
        val target = CompensationTarget(id = "compensation-id", function = eventFunction())
        val header = DefaultHeader.empty()

        val returned = header.withCompensation(target)

        returned.assert().isSameAs(header)
        header.compensationId.assert().isEqualTo("compensation-id")
        header[COMPENSATION_CONTEXT].assert().isEqualTo("context")
        header[COMPENSATION_PROCESSOR].assert().isEqualTo("processor")
        header[COMPENSATION_FUNCTION].assert().isEqualTo("function")
        header.isCompensation.assert().isTrue()
    }

    @Test
    fun `non compensation headers match every function`() {
        val header = DefaultHeader.empty()

        header.match(eventFunction()).assert().isTrue()
    }

    @Test
    fun `compensation headers match only the target function coordinates`() {
        val function = eventFunction()
        val header = DefaultHeader.empty().withCompensation(CompensationTarget(id = "id", function = function))

        header.match(function).assert().isTrue()
        header.match(function.copy(name = "other")).assert().isFalse()
        header.match(function.copy(processorName = "other")).assert().isFalse()
        header.match(function.copy(contextName = "other")).assert().isFalse()
    }

    @Test
    fun `message compensation delegates to its header`() {
        val function = eventFunction()
        val message = TestNamedMessage()

        val returned = message.withCompensation(CompensationTarget(id = "message-compensation", function = function))

        returned.assert().isSameAs(message)
        message.match(function).assert().isTrue()
        message.header.compensationId.assert().isEqualTo("message-compensation")
    }

    private fun eventFunction(): FunctionInfoData =
        FunctionInfoData(
            functionKind = FunctionKind.EVENT,
            contextName = "context",
            processorName = "processor",
            name = "function",
        )
}
