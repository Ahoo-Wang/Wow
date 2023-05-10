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
import java.util.concurrent.CopyOnWriteArraySet

class SimpleMultipleMessageFunctionRegistrar<H : MessageFunction<*, *, *>> : MultipleMessageFunctionRegistrar<H> {
    private companion object {
        private val log: Logger = LoggerFactory.getLogger(SimpleMultipleMessageFunctionRegistrar::class.java)
    }

    private val registrar: ConcurrentHashMap<Class<*>, MutableSet<H>> = ConcurrentHashMap()

    override fun register(function: H) {
        if (log.isInfoEnabled) {
            log.info("Register {}.", function)
        }
        registrar.compute(function.supportedType) { _, value ->
            var functions = value
            if (functions == null) {
                functions = CopyOnWriteArraySet()
            }
            functions.add(function)
            functions
        }
    }

    override fun unregister(function: H) {
        if (log.isInfoEnabled) {
            log.info("Unregister {}.", function)
        }
        registrar.compute(function.supportedType) { _, value ->
            if (null == value) {
                return@compute null
            }
            value.remove(function)
            if (value.isEmpty()) {
                return@compute null
            }
            value
        }
    }

    override fun getFunctions(supportedType: Class<*>): Set<H> {
        return registrar[supportedType] ?: emptySet()
    }

    override val functions: Set<H>
        get() = registrar.values.flatten().toSet()
}
