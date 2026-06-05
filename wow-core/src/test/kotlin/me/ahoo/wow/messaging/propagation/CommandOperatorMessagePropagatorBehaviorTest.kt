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

package me.ahoo.wow.messaging.propagation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.TestNamedMessage
import org.junit.jupiter.api.Test

class CommandOperatorMessagePropagatorBehaviorTest {

    @Test
    fun `propagate copies command operator when upstream has one`() {
        val upstream = TestNamedMessage(header = DefaultHeader.empty().withOperator("operator"))
        val target = DefaultHeader.empty()

        CommandOperatorMessagePropagator().propagate(target, upstream)

        target.operator.assert().isEqualTo("operator")
    }

    @Test
    fun `propagate leaves target unchanged when upstream has no operator`() {
        val target = DefaultHeader.empty()

        CommandOperatorMessagePropagator().propagate(target, TestNamedMessage())

        target.operator.assert().isNull()
    }
}
