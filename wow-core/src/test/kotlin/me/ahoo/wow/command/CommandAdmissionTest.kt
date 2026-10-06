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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.wait.WaitHandle
import me.ahoo.wow.command.wait.WaitPlan
import org.junit.jupiter.api.Test

class CommandAdmissionTest {
    private val admission = CommandAdmission(mockk(), mockk(), mockk())

    /** The message is built before the wait handle is registered: a failed build registers nothing. */
    @Test
    fun `a message that cannot be built registers no wait handle`() {
        val failure = IllegalStateException("copy failed")
        val command = mockk<CommandMessage<*>> {
            every { copy() } throws failure
        }
        var registered = 0

        assertThrownBy<IllegalStateException> {
            admission.registerWait(command, mockk<WaitPlan>()) {
                registered++
                mockk<WaitHandle>()
            }
        }.isSameAs(failure)

        registered.assert().isZero()
    }
}
