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

object RecoverableExceptionRegistrar {
    private val log = KotlinLogging.logger {}
    private val registrar = ConcurrentHashMap<Class<out Throwable>, RecoverableType>()

    fun register(throwableClass: Class<out Throwable>, recoverableType: RecoverableType) {
        val previous = registrar.put(throwableClass, recoverableType)
        log.info {
            "Register - throwableClass:[$throwableClass] - previous:[$previous],current:[$recoverableType]."
        }
    }

    fun unregister(throwableClass: Class<out Throwable>) {
        val removed = registrar.remove(throwableClass)
        log.info {
            "Unregister - throwableClass:[$throwableClass] - removed:[$removed]."
        }
    }

    fun getRecoverableType(throwableClass: Class<out Throwable>): RecoverableType? {
        return registrar[throwableClass]
    }
}
