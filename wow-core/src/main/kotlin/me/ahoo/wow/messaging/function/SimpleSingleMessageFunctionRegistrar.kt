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

package me.ahoo.wow.messaging.function

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

open class SimpleSingleMessageFunctionRegistrar<H : MessageFunction<*, *, *>> :
    SingleMessageFunctionRegistrar<H> {
    private companion object {
        private val log: Logger = LoggerFactory.getLogger(SimpleSingleMessageFunctionRegistrar::class.java)
    }

    private val registrar: ConcurrentHashMap<Class<*>, H> = ConcurrentHashMap()

    override fun register(function: H) {
        val previous = registrar.put(function.supportedType, function)
        if (log.isInfoEnabled) {
            log.info("Register {} previous:{}.", function, previous)
        }
    }

    override fun unregister(function: H) {
        if (log.isInfoEnabled) {
            log.info("Unregister {}.", function)
        }
        registrar.remove(function.supportedType)
    }

    override fun getFunction(supportedType: Class<*>): H? = registrar[supportedType]
    override val functions: Set<H>
        get() = registrar.values.toSet()
}
