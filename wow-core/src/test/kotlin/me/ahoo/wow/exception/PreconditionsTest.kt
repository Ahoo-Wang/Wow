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

package me.ahoo.wow.exception

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PreconditionsTest {

    @Test
    fun `should not evaluate lazy message when check succeeds`() {
        var evaluated = false

        Preconditions.check(true, ErrorCodes.ILLEGAL_STATE) {
            evaluated = true
            "should not happen"
        }

        evaluated.assert().isFalse()
    }

    @Test
    fun `should throw WowException with current error data when check fails`() {
        val exception = assertThrows<WowException> {
            Preconditions.check(false, ErrorCodes.ILLEGAL_ARGUMENT) {
                "invalid command"
            }
        }

        exception.errorCode.assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
        exception.errorMsg.assert().isEqualTo("invalid command")
        exception.bindingErrors.assert().isEqualTo(emptyList<Any>())
    }
}
