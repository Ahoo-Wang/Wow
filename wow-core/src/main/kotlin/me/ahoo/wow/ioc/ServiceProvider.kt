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
package me.ahoo.wow.ioc

import me.ahoo.wow.naming.annotation.toName
import kotlin.reflect.KType

/**
 * ServiceProvider .
 *
 * @author ahoo wang
 */
interface ServiceProvider {
    fun register(serviceName: String, serviceType: KType, service: Any)
    fun register(serviceType: KType, service: Any)
    fun register(serviceName: String, service: Any)

    fun <S : Any> getService(serviceType: KType): S?

    fun <S : Any> register(service: S) {
        val serviceName = service.javaClass.toName()
        register(serviceName, service)
    }
    fun <S : Any> register(serviceType: Class<S>, service: S)
    fun <S : Any> getService(serviceType: Class<S>): S?
    fun <S : Any> getService(serviceName: String): S?
    fun <S : Any> getRequiredService(serviceType: Class<S>): S {
        return requireNotNull(getService(serviceType)) { "ServiceType[$serviceType] not found." }
    }

    fun <S : Any> getRequiredService(serviceName: String): S {
        return requireNotNull(getService(serviceName)) { "ServiceName[$serviceName] not found." }
    }
}

inline fun <reified S : Any> ServiceProvider.getService(): S? {
    return getService(S::class.java)
}

inline fun <reified S : Any> ServiceProvider.getRequiredService(): S {
    return getRequiredService(S::class.java)
}
