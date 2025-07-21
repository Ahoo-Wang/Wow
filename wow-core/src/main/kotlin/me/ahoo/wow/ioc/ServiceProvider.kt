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

import me.ahoo.wow.api.Copyable
import me.ahoo.wow.naming.annotation.toName
import kotlin.reflect.KType
import kotlin.reflect.full.defaultType
import kotlin.reflect.typeOf

/**
 * ServiceProvider .
 *
 * @author ahoo wang
 */
interface ServiceProvider : Copyable<ServiceProvider> {
    fun register(
        service: Any,
        serviceName: String = service.javaClass.toName(),
        serviceType: KType = service.javaClass.kotlin.defaultType
    )

    fun <S : Any> getService(serviceType: KType): S?

    fun <S : Any> getService(serviceName: String): S?
}

inline fun <reified S : Any> ServiceProvider.register(
    service: Any,
    serviceName: String = service.javaClass.toName()
) {
    register(service, serviceName, typeOf<S>())
}

inline fun <reified S : Any> ServiceProvider.getService(): S? {
    return getService(typeOf<S>())
}

fun <S : Any> ServiceProvider.getRequiredService(serviceName: String): S {
    return requireNotNull(getService(serviceName)) {
        "Service[$serviceName] not found."
    }
}

fun <S : Any> ServiceProvider.getRequiredService(serviceType: KType): S {
    return requireNotNull(getService(serviceType)) {
        "Service[$serviceType] not found."
    }
}

fun <S : Any> ServiceProvider.getRequiredService(serviceType: Class<S>): S {
    return getRequiredService(serviceType.kotlin.defaultType)
}

inline fun <reified S : Any> ServiceProvider.getRequiredService(): S {
    return getRequiredService(typeOf<S>())
}
