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

package me.ahoo.wow.messaging.handler

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.command.CommandResultAccessor
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.filter.ErrorAccessor
import me.ahoo.wow.ioc.ServiceProvider
import reactor.core.publisher.Mono
import kotlin.reflect.KType
import kotlin.reflect.jvm.jvmErasure

const val ERROR_KEY = "__ERROR__"
const val SERVICE_PROVIDER_KEY = "__SERVICE_PROVIDER__"
const val FUNCTION_KEY = "__FUNCTION__"
const val COMMAND_RESULT_KEY = "__COMMAND_RESULT__"

@Suppress("TooManyFunctions")
interface MessageExchange<SOURCE : MessageExchange<SOURCE, M>, out M : Message<*, *>> :
    ErrorAccessor,
    CommandResultAccessor {
    val attributes: MutableMap<String, Any>
    val message: M
    fun acknowledge(): Mono<Void> = Mono.empty()

    fun setAttribute(key: String, value: Any): SOURCE {
        attributes[key] = value
        @Suppress("UNCHECKED_CAST")
        return this as SOURCE
    }

    fun <T> getAttribute(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return attributes[key] as T?
    }

    fun removeAttribute(key: String): SOURCE {
        attributes.remove(key)
        @Suppress("UNCHECKED_CAST")
        return this as SOURCE
    }

    override fun setError(throwable: Throwable) {
        setAttribute(ERROR_KEY, throwable)
    }

    override fun getError(): Throwable? {
        return getAttribute(ERROR_KEY)
    }

    override fun clearError() {
        removeAttribute(ERROR_KEY)
    }

    fun setFunction(functionInfo: FunctionInfo): SOURCE {
        return setAttribute(FUNCTION_KEY, functionInfo)
    }

    fun getFunction(): FunctionInfo? {
        return getAttribute(FUNCTION_KEY)
    }

    fun <FUN : FunctionInfo> getFunctionAs(): FUN? {
        return getAttribute(FUNCTION_KEY)
    }

    fun setServiceProvider(serviceProvider: ServiceProvider): SOURCE {
        return setAttribute(SERVICE_PROVIDER_KEY, serviceProvider)
    }

    fun getServiceProvider(): ServiceProvider? {
        return getAttribute(SERVICE_PROVIDER_KEY)
    }

    fun getAggregateVersion(): Int? {
        if (message is Version) {
            return (message as Version).version
        }
        return null
    }

    override fun getCommandResult(): Map<String, Any> {
        return getAttribute<Map<String, Any>>(COMMAND_RESULT_KEY) ?: emptyMap()
    }

    override fun <R> getCommandResult(key: String): R? {
        @Suppress("UNCHECKED_CAST")
        return getCommandResult()[key] as R?
    }

    override fun setCommandResult(key: String, value: Any) {
        val commandResult = getAttribute<Map<String, Any>>(COMMAND_RESULT_KEY)
        val newCommandResult = if (commandResult == null) {
            mapOf(key to value)
        } else {
            commandResult + (key to value)
        }
        setAttribute(COMMAND_RESULT_KEY, newCommandResult)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> extractObject(type: KType): T? {
        return extractDeclared(type.jvmErasure.java as Class<T>) ?: getServiceProvider()?.getService(type)
    }

    @Suppress("ReturnCount")
    fun <T : Any> extractDeclared(type: Class<T>): T? {
        if (type.isInstance(this)) {
            return type.cast(this)
        }
        val exMessage = message
        if (type.isInstance(exMessage)) {
            return type.cast(exMessage)
        }
        if (type.isInstance(exMessage.header)) {
            return type.cast(exMessage.header)
        }
        if (exMessage is AggregateIdCapable) {
            if (type.isInstance(exMessage.aggregateId)) {
                return type.cast(exMessage.aggregateId)
            }
        }
        val serviceProvider = getServiceProvider()
        if (type.isInstance(serviceProvider)) {
            return type.cast(serviceProvider)
        }

        return null
    }
}
