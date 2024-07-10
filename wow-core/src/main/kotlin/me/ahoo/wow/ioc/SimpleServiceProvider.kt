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

import java.util.concurrent.ConcurrentHashMap

class SimpleServiceProvider : ServiceProvider {
    private val typedServices: ConcurrentHashMap<Class<*>, Any> = ConcurrentHashMap<Class<*>, Any>()
    private val namedServices: ConcurrentHashMap<String, Any> = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> getService(serviceType: Class<S>): S? {
        val service = typedServices[serviceType] as S?
        if (service != null) {
            return service
        }
        return typedServices.values.firstOrNull { serviceType.isInstance(it) } as S?
    }

    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> getService(serviceName: String): S? {
        return namedServices[serviceName] as S?
    }

    override fun <S : Any> register(serviceType: Class<S>, service: S) {
        typedServices[serviceType] = service
    }

    override fun <S : Any> register(serviceName: String, service: S) {
        namedServices[serviceName] = service
        val serviceType = service.javaClass
        register(serviceType, service)
    }

    fun copy(): SimpleServiceProvider {
        val copy = SimpleServiceProvider()
        copy.typedServices.putAll(typedServices)
        copy.namedServices.putAll(namedServices)
        return copy
    }
}
