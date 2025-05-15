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
import me.ahoo.wow.exception.AbstractErrorConverterFactory
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.ErrorConverter
import org.springframework.web.reactive.resource.NoResourceFoundException

object NoResourceFoundExceptionConverter : ErrorConverter<NoResourceFoundException> {
    override fun convert(error: NoResourceFoundException): ErrorInfo {
        return ErrorInfo.of(ErrorCodes.NOT_FOUND, errorMsg = error.message)
    }
}

class NoResourceFoundExceptionConverterFactory : AbstractErrorConverterFactory<NoResourceFoundException>() {
    override fun create(): ErrorConverter<NoResourceFoundException> {
        return NoResourceFoundExceptionConverter
    }
}
