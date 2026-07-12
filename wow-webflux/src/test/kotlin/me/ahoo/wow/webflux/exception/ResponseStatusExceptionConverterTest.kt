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

package me.ahoo.wow.webflux.exception

import me.ahoo.test.asserts.assert
import me.ahoo.wow.exception.toErrorInfo
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.web.server.ResponseStatusException

class ResponseStatusExceptionConverterTest {
    @Test
    fun `should convert standard response statuses to stable error codes`() {
        listOf(
            HttpStatus.NOT_FOUND to "NotFound",
            HttpStatus.UNSUPPORTED_MEDIA_TYPE to "UnsupportedMediaType",
            HttpStatus.BAD_REQUEST to "BadRequest",
        ).forEach { (status, expectedErrorCode) ->
            val error = ResponseStatusException(status, "status reason")
            val errorInfo = error.toErrorInfo()

            errorInfo.errorCode.assert().isEqualTo(expectedErrorCode)
            errorInfo.errorMsg.assert().isEqualTo(error.message)
        }
    }

    @Test
    fun `should convert non-standard response status to stable error code`() {
        val error = ResponseStatusException(HttpStatusCode.valueOf(499), "status reason")
        val errorInfo = error.toErrorInfo()

        errorInfo.errorCode.assert().isEqualTo("HttpStatus499")
        errorInfo.errorMsg.assert().isEqualTo(error.message)
    }
}
