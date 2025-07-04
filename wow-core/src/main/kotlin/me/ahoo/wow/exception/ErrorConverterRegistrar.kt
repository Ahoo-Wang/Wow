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

object ErrorConverterRegistrar {
    private val log = KotlinLogging.logger {}
    private val registrar = ConcurrentHashMap<Class<out Throwable>, ErrorConverter<Throwable>>()

    init {
        ServiceLoader.load(ErrorConverterFactory::class.java).sortedByOrder()
            .forEach {
                register(it)
            }
    }

    fun register(factory: ErrorConverterFactory<out Throwable>): ErrorConverter<Throwable>? {
        return register(factory.supportedType, factory.create() as ErrorConverter<Throwable>)
    }

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

    fun unregister(throwableClass: Class<out Throwable>): ErrorConverter<Throwable>? {
        val removed = registrar.remove(throwableClass)
        log.info {
            "Unregister - throwableClass:[$throwableClass] - removed:[$removed]."
        }
        return removed
    }

    fun get(throwableClass: Class<out Throwable>): ErrorConverter<Throwable>? {
        return registrar[throwableClass]
    }
}

fun Throwable.toErrorInfo(): ErrorInfo {
    val errorConverter = ErrorConverterRegistrar.get(this.javaClass) ?: DefaultErrorConverter
    return errorConverter.convert(this)
}
