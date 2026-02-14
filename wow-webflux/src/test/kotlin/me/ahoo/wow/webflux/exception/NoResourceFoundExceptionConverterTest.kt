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
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.toErrorInfo
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.resource.NoResourceFoundException
import java.net.URI

class NoResourceFoundExceptionConverterTest {
    @Test
    fun convert() {
        val error = NoResourceFoundException(URI.create(""), "resourcePath")
        val errorInfo = error.toErrorInfo()
        errorInfo.errorCode.assert().isEqualTo(ErrorCodes.NOT_FOUND)
        errorInfo.errorMsg.assert().isEqualTo(error.message)
    }
}
