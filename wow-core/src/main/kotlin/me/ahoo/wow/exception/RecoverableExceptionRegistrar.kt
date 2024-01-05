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

import me.ahoo.wow.api.exception.RecoverableType
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object RecoverableExceptionRegistrar {
    private val log = LoggerFactory.getLogger(RecoverableExceptionRegistrar::class.java)
    private val registrar = ConcurrentHashMap<Class<out Throwable>, RecoverableType>()

    fun register(throwableClass: Class<out Throwable>, recoverableType: RecoverableType) {
        val previous = registrar.put(throwableClass, recoverableType)
        if (log.isInfoEnabled) {
            log.info(
                "Register - throwableClass:[{}] - previous:[{}],current:[{}].",
                throwableClass,
                recoverableType,
                previous
            )
        }
    }

    fun unregister(throwableClass: Class<out Throwable>) {
        val removed = registrar.remove(throwableClass)
        if (log.isInfoEnabled) {
            log.info("Unregister - throwableClass:[{}] - removed:[{}].", throwableClass, removed)
        }
    }

    fun getRecoverableType(throwableClass: Class<out Throwable>): RecoverableType? {
        return registrar[throwableClass]
    }
}
