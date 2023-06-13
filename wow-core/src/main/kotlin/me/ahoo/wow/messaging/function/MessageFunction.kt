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

import me.ahoo.wow.api.messaging.FunctionKindCapable
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.messaging.handler.MessageExchange

interface MessageFunction<P : Any, in M : MessageExchange<*, *>, out R> : FunctionKindCapable, NamedBoundedContext {

    /**
     * Message body types supported by the message function.
     */
    val supportedType: Class<*>

    /**
     * @see me.ahoo.wow.api.annotation.OnEvent.value
     */
    val supportedTopics: Set<Any>
        get() = emptySet()
    val processor: P

    fun handle(exchange: M): R
}

interface MethodMessageFunction<P : Any, in M : MessageExchange<*, *>, out R> : MessageFunction<P, M, R> {
    val metadata: MethodFunctionMetadata<P, R>

    @Suppress("UNCHECKED_CAST")
    override val supportedType: Class<Any>
        get() {
            return metadata.supportedType as Class<Any>
        }
}

fun <P : Any, M : MessageExchange<*, *>, R> MethodFunctionMetadata<P, R>.asMessageFunction(processor: P): MethodMessageFunction<P, M, R> {
    return if (injectParameterLength == 0) {
        SimpleMethodMessageFunction(processor, this)
    } else {
        InjectableMethodMessageFunction(processor, this)
    }
}
