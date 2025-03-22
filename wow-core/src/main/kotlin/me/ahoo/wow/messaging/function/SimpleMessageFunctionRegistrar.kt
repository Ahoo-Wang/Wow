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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import java.util.concurrent.CopyOnWriteArraySet

class SimpleMessageFunctionRegistrar<F : MessageFunction<*, *, *>> : MessageFunctionRegistrar<F> {
    private companion object {
        private val log = KotlinLogging.logger {}
    }

    private val registrar: CopyOnWriteArraySet<F> = CopyOnWriteArraySet()

    override fun register(function: F) {
        log.info {
            "Register $function."
        }
        registrar.add(function)
    }

    override fun unregister(function: F) {
        log.info {
            "Unregister $function."
        }
        registrar.remove(function)
    }

    override val functions: Set<F>
        get() = registrar

    override fun <M> supportedFunctions(message: M): Sequence<F>
        where M : Message<*, Any>, M : NamedBoundedContext, M : NamedAggregate {
        return functions.asSequence()
            .filter {
                it.supportMessage(message)
            }
    }
}
