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

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.exception.ErrorInfo.Companion.materialize
import me.ahoo.wow.api.exception.ErrorInfoCapable
import java.io.FileNotFoundException
import java.lang.reflect.ParameterizedType
import java.util.concurrent.TimeoutException

fun interface ErrorConverter<E : Throwable> {
    fun convert(error: E): ErrorInfo
}

interface ErrorConverterFactory<E : Throwable> {
    val supportedType: Class<E>
    fun create(): ErrorConverter<E>
}

abstract class AbstractErrorConverterFactory<E : Throwable> : ErrorConverterFactory<E> {
    @Suppress("UNCHECKED_CAST")
    override val supportedType: Class<E> by lazy {
        val superType = javaClass.genericSuperclass as ParameterizedType
        superType.actualTypeArguments[0] as Class<E>
    }
}

object DefaultErrorConverter : ErrorConverter<Throwable> {
    override fun convert(error: Throwable): ErrorInfo {
        if (error is ErrorInfoCapable) {
            return error.errorInfo.materialize()
        }
        if (error is ErrorInfo) {
            return error.materialize()
        }
        val errorCode = when (error) {
            is IllegalArgumentException -> ErrorCodes.ILLEGAL_ARGUMENT
            is IllegalStateException -> ErrorCodes.ILLEGAL_STATE
            is TimeoutException -> ErrorCodes.REQUEST_TIMEOUT
            is FileNotFoundException -> ErrorCodes.NOT_FOUND
            else -> ErrorCodes.BAD_REQUEST
        }
        return ErrorInfo.of(errorCode, error.message)
    }
}
