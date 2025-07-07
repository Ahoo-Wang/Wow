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
import me.ahoo.wow.openapi.CommonComponent
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpResponse

class HttpHeadersGuardTest {

    @Test
    fun trySetHeader() {
        val response = MockServerHttpResponse()
        response.trySetHeader(CommonComponent.Header.WOW_ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT).assert().isTrue()
        response.headers.getFirst(CommonComponent.Header.WOW_ERROR_CODE).assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
        response.isCommitted.assert().isFalse()
        response.setComplete().block()
        response.isCommitted.assert().isTrue()
        response.trySetHeader(CommonComponent.Header.WOW_ERROR_CODE, ErrorCodes.NOT_FOUND).assert().isFalse()
        response.headers.getFirst(CommonComponent.Header.WOW_ERROR_CODE).assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
    }

    @Test
    fun trySet() {
        val httpHeaders = HttpHeaders()
        httpHeaders.trySet(CommonComponent.Header.WOW_ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT).assert().isTrue()
        httpHeaders.getFirst(CommonComponent.Header.WOW_ERROR_CODE).assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
    }

    @Test
    fun trySetIfReadOnly() {
        val httpHeaders = HttpHeaders()
        val readOnlyHttpHeaders = HttpHeaders.readOnlyHttpHeaders(httpHeaders)
        readOnlyHttpHeaders.trySet(
            CommonComponent.Header.WOW_ERROR_CODE,
            ErrorCodes.ILLEGAL_ARGUMENT
        ).assert().isFalse()
        readOnlyHttpHeaders.getFirst(CommonComponent.Header.WOW_ERROR_CODE).assert().isNull()
    }
}
