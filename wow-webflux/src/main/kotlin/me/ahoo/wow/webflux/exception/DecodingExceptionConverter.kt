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

import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.exception.AbstractErrorInfoConverterFactory
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.ErrorInfoConverter
import org.springframework.core.codec.DecodingException

object DecodingExceptionConverter : ErrorInfoConverter<DecodingException> {
    override fun convert(error: DecodingException): ErrorInfo {
        return ErrorInfo.of(
            errorCode = ErrorCodes.ILLEGAL_ARGUMENT,
            errorMsg = error.message,
            bindingErrors = listOf(
                BindingError("body", error.cause?.message.orEmpty())
            )
        )
    }
}

class DecodingExceptionConverterFactory : AbstractErrorInfoConverterFactory<DecodingException>() {
    override fun create(): ErrorInfoConverter<DecodingException> {
        return DecodingExceptionConverter
    }
}
