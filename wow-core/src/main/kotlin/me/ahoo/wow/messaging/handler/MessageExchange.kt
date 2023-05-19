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

import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.ioc.ServiceProvider

const val ERROR_KEY = "__ERROR__"
const val SERVICE_PROVIDER_KEY = "__SERVICE_PROVIDER__"

interface MessageExchange<SOURCE : MessageExchange<SOURCE, M>, out M : Message<*>> {
    val attributes: MutableMap<String, Any>
    val message: M
    fun acknowledge() = Unit

    fun setAttribute(key: String, value: Any): SOURCE {
        attributes[key] = value
        @Suppress("UNCHECKED_CAST")
        return this as SOURCE
    }

    fun <T> getAttribute(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return attributes[key] as T?
    }

    fun setError(throwable: Throwable): SOURCE {
        attributes[ERROR_KEY] = throwable
        return setAttribute(ERROR_KEY, throwable)
    }

    fun getError(): Throwable? {
        return getAttribute(ERROR_KEY)
    }

    fun setServiceProvider(serviceProvider: ServiceProvider): SOURCE {
        return setAttribute(SERVICE_PROVIDER_KEY, serviceProvider)
    }

    fun getServiceProvider(): ServiceProvider? {
        return getAttribute(SERVICE_PROVIDER_KEY)
    }

    fun <T : Any> extractObject(type: Class<T>): T? {
        return extractDeclared(type) ?: getServiceProvider()?.getService(type)
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

    fun <T : Any> extractRequiredObject(type: Class<T>): T {
        return requireNotNull(extractObject(type)) { "Type[$type] not found." }
    }
}
