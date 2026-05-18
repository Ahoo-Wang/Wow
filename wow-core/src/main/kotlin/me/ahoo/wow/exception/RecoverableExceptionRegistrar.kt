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
import me.ahoo.wow.api.exception.RecoverableType
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * SPI interface for registering recoverable exception classifications.
 *
 * Implementations are discovered via Java's [ServiceLoader] mechanism.
 * Each provider can register custom exception-to-[RecoverableType] mappings
 * into the shared [RecoverableExceptionRegistrar].
 */
interface RecoverableExceptionProvider {
    fun register(registrar: RecoverableExceptionRegistrar)
}

/**
 * Global registry that maps exception classes to their [RecoverableType] classifications.
 *
 * On initialization, it uses [ServiceLoader] to discover all [RecoverableExceptionProvider]
 * implementations on the classpath and delegates registration to each one.
 * The backing store is a [ConcurrentHashMap], so registration and lookup are thread-safe.
 *
 * The registry is consulted by the [Class.recoverable] extension property when determining
 * whether an exception can be retried. Explicit registrations here take precedence over
 * the default rules (e.g., [RecoverableException] marker interface, [TimeoutException]).
 *
 * @see RecoverableExceptionProvider
 * @see RecoverableType
 * @see Class.recoverable
 */
object RecoverableExceptionRegistrar {
    private val log = KotlinLogging.logger {}
    private val registrar = ConcurrentHashMap<Class<out Throwable>, RecoverableType>()

    init {
        ServiceLoader
            .load(RecoverableExceptionProvider::class.java)
            .forEach {
                it.register(this)
            }
    }

    /**
     * Registers (or overwrites) the [RecoverableType] for the given exception class.
     *
     * @param throwableClass the exception class to classify
     * @param recoverableType the recoverability classification
     */
    fun register(
        throwableClass: Class<out Throwable>,
        recoverableType: RecoverableType
    ) {
        val previous = registrar.put(throwableClass, recoverableType)
        log.info {
            "Register - throwableClass:[$throwableClass] - previous:[$previous],current:[$recoverableType]."
        }
    }

    /**
     * Removes the registration for the given exception class.
     *
     * @param throwableClass the exception class to unregister
     */
    fun unregister(throwableClass: Class<out Throwable>) {
        val removed = registrar.remove(throwableClass)
        log.info {
            "Unregister - throwableClass:[$throwableClass] - removed:[$removed]."
        }
    }

    /**
     * Returns the explicitly registered [RecoverableType] for the given exception class,
     * or `null` if no registration exists.
     */
    fun getRecoverableType(throwableClass: Class<out Throwable>): RecoverableType? = registrar[throwableClass]
}
