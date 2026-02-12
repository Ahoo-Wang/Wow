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

/**
 * Functional interface for converting exceptions to standardized error information.
 *
 * Implementations of this interface define how specific exception types should be
 * transformed into ErrorInfo objects for consistent error reporting and handling.
 *
 * @param E the type of exception this converter can handle
 * @see ErrorInfo
 */
fun interface ErrorConverter<E : Throwable> {
    /**
     * Converts an exception to an ErrorInfo object.
     *
     * @param error the exception to convert
     * @return the standardized error information
     */
    fun convert(error: E): ErrorInfo
}

/**
 * Factory interface for creating ErrorConverter instances.
 *
 * This interface allows for dynamic creation of error converters and provides
 * type information about the exceptions they can handle.
 *
 * @param E the type of exception the created converter can handle
 * @see ErrorConverter
 */
interface ErrorConverterFactory<E : Throwable> {
    /**
     * The class of exceptions this factory can create converters for.
     */
    val supportedType: Class<E>

    /**
     * Creates a new ErrorConverter instance.
     *
     * @return a new error converter for the supported exception type
     */
    fun create(): ErrorConverter<E>
}

/**
 * Abstract base class for ErrorConverterFactory implementations.
 *
 * This class provides automatic type resolution for the supported exception type
 * using reflection on the generic type parameter. Subclasses only need to implement
 * the create() method.
 *
 * @param E the type of exception the factory handles
 * @see ErrorConverterFactory
 */
abstract class AbstractErrorConverterFactory<E : Throwable> : ErrorConverterFactory<E> {
    /**
     * Automatically resolves the supported exception type from the generic type parameter.
     */
    @Suppress("UNCHECKED_CAST")
    override val supportedType: Class<E> by lazy {
        val superType = javaClass.genericSuperclass as ParameterizedType
        superType.actualTypeArguments[0] as Class<E>
    }
}

/**
 * Default error converter that handles common exception types.
 *
 * This converter provides standard mappings from common Java exceptions to Wow error codes.
 * It handles ErrorInfoCapable exceptions, direct ErrorInfo instances, and maps standard
 * exceptions like IllegalArgumentException, IllegalStateException, etc. to appropriate error codes.
 *
 * For exceptions that don't have specific mappings, it defaults to BAD_REQUEST.
 *
 * Example usage:
 * ```kotlin
 * val errorInfo = DefaultErrorConverter.convert(IllegalArgumentException("Invalid input"))
 * // Returns ErrorInfo with code "IllegalArgument"
 * ```
 *
 * @see ErrorConverter
 * @see ErrorCodes
 */
object DefaultErrorConverter : ErrorConverter<Throwable> {
    /**
     * Converts a Throwable to ErrorInfo using standard mappings.
     *
     * @param error the exception to convert
     * @return the converted error information
     */
    override fun convert(error: Throwable): ErrorInfo {
        if (error is ErrorInfoCapable) {
            return error.errorInfo.materialize()
        }
        if (error is ErrorInfo) {
            return error.materialize()
        }
        val errorCode =
            when (error) {
                is IllegalArgumentException -> ErrorCodes.ILLEGAL_ARGUMENT
                is IllegalStateException -> ErrorCodes.ILLEGAL_STATE
                is TimeoutException -> ErrorCodes.REQUEST_TIMEOUT
                is FileNotFoundException -> ErrorCodes.NOT_FOUND
                else -> ErrorCodes.BAD_REQUEST
            }
        return ErrorInfo.of(errorCode, error.message)
    }
}
