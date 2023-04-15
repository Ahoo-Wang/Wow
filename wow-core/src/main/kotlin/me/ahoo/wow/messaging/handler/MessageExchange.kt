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
import me.ahoo.wow.ioc.ServiceProvider

interface MessageExchange<out M : Message<*>> {
    val message: M
    fun acknowledge() = Unit
    var serviceProvider: ServiceProvider?

    fun <T : Any> extractObject(type: Class<T>): T? {
        return extractDeclared(type) ?: serviceProvider?.getService(type)
    }

    fun <T : Any> extractDeclared(type: Class<T>): T? {
        if (type.isInstance(this)) {
            return type.cast(this)
        }
        if (type.isInstance(message)) {
            return type.cast(message)
        }
        if (type.isInstance(message.header)) {
            return type.cast(message.header)
        }
        if (type.isInstance(serviceProvider)) {
            return type.cast(serviceProvider)
        }
        return null
    }

    fun <T : Any> extractRequiredObject(type: Class<T>): T {
        return requireNotNull(extractObject(type)) { "Type[$type] not found." }
    }
}
