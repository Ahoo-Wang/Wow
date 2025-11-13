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
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for managing recoverable exception classifications.
 *
 * This object maintains a mapping of exception classes to their RecoverableType,
 * allowing the framework to determine whether specific exceptions can be recovered
 * from through retry mechanisms.
 *
 * @see RecoverableType
 */
object RecoverableExceptionRegistrar {
    private val log = KotlinLogging.logger {}
    private val registrar = ConcurrentHashMap<Class<out Throwable>, RecoverableType>()

    /**
     * Registers a recoverable type classification for an exception class.
     *
     * This method associates an exception class with a specific RecoverableType,
     * overriding any default classification for that exception type.
     *
     * @param throwableClass the exception class to classify
     * @param recoverableType the recoverable classification for this exception type
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
     * Unregisters the recoverable type classification for an exception class.
     *
     * This method removes any custom classification for the given exception class,
     * causing it to fall back to the default classification logic.
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
     * Retrieves the registered recoverable type for an exception class.
     *
     * @param throwableClass the exception class to look up
     * @return the registered RecoverableType, or null if not explicitly registered
     */
    fun getRecoverableType(throwableClass: Class<out Throwable>): RecoverableType? = registrar[throwableClass]
}
