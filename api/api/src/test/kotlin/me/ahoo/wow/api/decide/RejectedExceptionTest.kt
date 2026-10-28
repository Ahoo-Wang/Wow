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

package me.ahoo.wow.api.decide

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class RejectedExceptionTest {
    @Test
    fun `a thrown rejection should carry its rejection and message, without a stack trace`() {
        val rejection = Rejection.of("frozen", "The account is frozen.")
        val thrown = RejectedException(rejection)
        thrown.rejection.assert().isSameAs(rejection)
        thrown.message.assert().isEqualTo("The account is frozen.")
        thrown.stackTrace.assert().isEmpty()
    }

    @Test
    fun `reject of a scope should throw the rejection it names`() {
        val scope = mockk<DecideScope> {
            every { reject(any(), any()) } answers { callOriginal() }
            every { reject(any(), any(), extensions = any()) } answers { callOriginal() }
        }
        assertThrownBy<RejectedException> { scope.reject("frozen", "The account is frozen.") }
            .actual().rejection.assert().isEqualTo(Rejection.of("frozen", "The account is frozen."))
        assertThrownBy<RejectedException> { scope.reject("short", "Only 2.", extensions = mapOf("left" to 2)) }
            .actual().rejection.extensions.assert().containsEntry("left", 2)
        assertThrownBy<IllegalArgumentException> { scope.reject("wow.command.busy", "No.") }
    }
}
