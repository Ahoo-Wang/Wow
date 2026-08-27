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

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.exception.AbstractErrorInfoConverterFactory
import me.ahoo.wow.exception.ErrorInfoConverter
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Locale

object ResponseStatusExceptionConverter : ErrorInfoConverter<ResponseStatusException> {
    override fun convert(error: ResponseStatusException): ErrorInfo {
        val errorCode = HttpStatus.resolve(error.statusCode.value())
            ?.name
            ?.split('_')
            ?.joinToString(separator = "") { word ->
                word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
            ?: "HttpStatus${error.statusCode.value()}"
        return ErrorInfo.of(errorCode, error.message)
    }
}

class ResponseStatusExceptionConverterFactory : AbstractErrorInfoConverterFactory<ResponseStatusException>() {
    override fun create(): ErrorInfoConverter<ResponseStatusException> {
        return ResponseStatusExceptionConverter
    }
}
