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
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.toErrorInfo
import org.junit.jupiter.api.Test
import org.springframework.web.server.ServerWebInputException

class ServerWebInputExceptionConverterTest {
    @Test
    fun convert() {
        val error = ServerWebInputException(
            "input decode error",
            null,
            IllegalArgumentException("iae")
        )
        val errorInfo = error.toErrorInfo()
        errorInfo.errorCode.assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
        errorInfo.errorMsg.assert().isEqualTo(error.message)
        errorInfo.bindingErrors.assert().isNotEmpty()
            .first().isEqualTo(BindingError("body", "iae"))
    }
}
