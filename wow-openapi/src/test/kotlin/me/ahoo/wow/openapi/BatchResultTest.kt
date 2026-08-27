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

package me.ahoo.wow.openapi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.ErrorInfo
import org.junit.jupiter.api.Test

internal class BatchResultTest {

    @Test
    fun `should create batch result with after id and size`() {
        val batchResult = BatchResult("cursorId", 10)
        batchResult.afterId.assert().isEqualTo("cursorId")
        batchResult.size.assert().isEqualTo(10)
    }

    @Test
    fun `should use default error code and message`() {
        val batchResult = BatchResult("id", 1)
        batchResult.errorCode.assert().isEqualTo(ErrorInfo.SUCCEEDED)
        batchResult.errorMsg.assert().isEqualTo(ErrorInfo.SUCCEEDED_MESSAGE)
    }

    @Test
    fun `should implement ErrorInfo interface`() {
        val batchResult = BatchResult("id", 0)
        batchResult.assert().isInstanceOf(ErrorInfo::class.java)
    }
}
