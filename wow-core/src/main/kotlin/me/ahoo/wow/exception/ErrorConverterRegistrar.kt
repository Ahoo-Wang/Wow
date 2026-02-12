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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.api.exception.ErrorInfo
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for managing error converters by exception type.
 *
 * This object maintains a mapping of exception classes to their corresponding ErrorConverter
 * implementations. It automatically loads and registers converters from the ServiceLoader
 * during initialization, and provides methods for manual registration and lookup.
 *
 * The registrar uses a thread-safe ConcurrentHashMap to ensure safe concurrent access.
 *
 * @see ErrorConverter
 * @see ErrorConverterFactory
 */
object ErrorConverterRegistrar {
    private val log = KotlinLogging.logger {}
    private val registrar = ConcurrentHashMap<Class<out Throwable>, ErrorConverter<Throwable>>()

    init {
        ServiceLoader
            .load(ErrorConverterFactory::class.java)
            .sortedByOrder()
            .forEach {
                register(it)
            }
    }

    /**
     * Registers an error converter using a factory.
     *
     * This method creates an ErrorConverter instance from the factory and registers it
     * for the factory's supported exception type.
     *
     * @param factory the factory to create the converter from
     * @return the previously registered converter for this exception type, or null if none existed
     */
    fun register(factory: ErrorConverterFactory<out Throwable>): ErrorConverter<Throwable>? =
        register(factory.supportedType, factory.create() as ErrorConverter<Throwable>)

    /**
     * Registers an error converter for a specific exception type.
     *
     * This method directly registers a converter instance for the given exception class.
     * If a converter was already registered for this type, it will be replaced.
     *
     * @param throwableClass the exception class this converter handles
     * @param errorConverter the converter to register
     * @return the previously registered converter for this exception type, or null if none existed
     */
    fun register(
        throwableClass: Class<out Throwable>,
        errorConverter: ErrorConverter<Throwable>
    ): ErrorConverter<Throwable>? {
        val previous = registrar.put(throwableClass, errorConverter)
        log.info {
            "Register - throwableClass:[$throwableClass] - previous:[$previous],current:[$errorConverter]."
        }
        return previous
    }

    /**
     * Unregisters the error converter for a specific exception type.
     *
     * This method removes any registered converter for the given exception class.
     *
     * @param throwableClass the exception class to unregister
     * @return the converter that was removed, or null if none was registered
     */
    fun unregister(throwableClass: Class<out Throwable>): ErrorConverter<Throwable>? {
        val removed = registrar.remove(throwableClass)
        log.info {
            "Unregister - throwableClass:[$throwableClass] - removed:[$removed]."
        }
        return removed
    }

    /**
     * Retrieves the error converter for a specific exception type.
     *
     * @param throwableClass the exception class to look up
     * @return the registered converter for this exception type, or null if none is registered
     */
    fun get(throwableClass: Class<out Throwable>): ErrorConverter<Throwable>? = registrar[throwableClass]
}

/**
 * Converts this Throwable to standardized ErrorInfo.
 *
 * This extension function looks up the appropriate error converter for this exception's type
 * and uses it to convert the exception to ErrorInfo. If no specific converter is registered,
 * it falls back to the DefaultErrorConverter.
 *
 * Example usage:
 * ```kotlin
 * try {
 *     // some operation
 * } catch (e: Exception) {
 *     val errorInfo = e.toErrorInfo()
 *     // handle standardized error
 * }
 * ```
 *
 * @receiver the exception to convert
 * @return the standardized error information
 * @see ErrorInfo
 * @see DefaultErrorConverter
 */
fun Throwable.toErrorInfo(): ErrorInfo {
    val errorConverter = ErrorConverterRegistrar.get(this.javaClass) ?: DefaultErrorConverter
    return errorConverter.convert(this)
}
