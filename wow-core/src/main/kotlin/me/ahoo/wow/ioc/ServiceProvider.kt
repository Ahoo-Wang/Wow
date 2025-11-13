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
 * Service provider interface for dependency injection and service management in the Wow framework.
 * Provides methods to register services by type and name, and retrieve them when needed.
 * This interface supports both type-based and name-based service resolution.
 *
 * Implementations should provide thread-safe operations for concurrent access.
 *
 * @author ahoo wang
 */
interface ServiceProvider : Copyable<ServiceProvider> {
    /**
     * Set of all registered service names.
     * This provides a way to discover what services are available in the provider.
     */
    val serviceNames: Set<String>

    /**
     * Registers a service instance with the provider.
     * The service can be registered with a custom name and type, or use defaults derived from the service instance.
     *
     * @param service the service instance to register
     * @param serviceName the name to register the service under (defaults to class name converted to naming convention)
     * @param serviceType the Kotlin type of the service (defaults to the service's class default type)
     */
    fun register(
        service: Any,
        serviceName: String = service.javaClass.toName(),
        serviceType: KType = service.javaClass.kotlin.defaultType
    )

    /**
     * Retrieves a service by its Kotlin type.
     * Returns null if no service of the specified type is registered.
     *
     * @param S the type of service to retrieve
     * @param serviceType the Kotlin type to look up
     * @return the service instance of type S, or null if not found
     */
    fun <S : Any> getService(serviceType: KType): S?

    /**
     * Retrieves a service by its registered name.
     * Returns null if no service with the specified name is registered.
     *
     * @param S the type of service to retrieve
     * @param serviceName the name of the service to look up
     * @return the service instance of type S, or null if not found
     */
    fun <S : Any> getService(serviceName: String): S?

    /**
     * Copies all registered services from this provider to the target provider.
     * This is useful for merging service providers or creating combined providers.
     *
     * @param target the provider to copy services to
     */
    fun copyTo(target: ServiceProvider)
}

/**
 * Registers a service with reified type information.
 * This is a convenience extension that automatically infers the service type using reified generics.
 *
 * @param S the type of service being registered
 * @param service the service instance to register
 * @param serviceName the name to register the service under (defaults to class name converted to naming convention)
 *
 * @sample
 * ```
 * val provider = SimpleServiceProvider()
 * val myService = MyService()
 * provider.register<MyServiceInterface>(myService, "myService")
 * ```
 */
inline fun <reified S : Any> ServiceProvider.register(
    service: Any,
    serviceName: String = service.javaClass.toName()
) {
    register(service, serviceName, typeOf<S>())
}

/**
 * Retrieves a service using reified type information.
 * This is a convenience extension that automatically uses the reified type for lookup.
 *
 * @param S the type of service to retrieve
 * @return the service instance of type S, or null if not found
 *
 * @sample
 * ```
 * val provider = SimpleServiceProvider()
 * val service: MyServiceInterface? = provider.getService()
 * ```
 */
inline fun <reified S : Any> ServiceProvider.getService(): S? = getService(typeOf<S>())

/**
 * Retrieves a required service by name, throwing an exception if not found.
 * This method guarantees that a service is returned or an exception is thrown.
 *
 * @param S the type of service to retrieve
 * @param serviceName the name of the service to look up
 * @return the service instance of type S
 * @throws IllegalArgumentException if the service is not found
 *
 * @sample
 * ```
 * val provider = SimpleServiceProvider()
 * val service = provider.getRequiredService<MyService>("myService")
 * ```
 */
fun <S : Any> ServiceProvider.getRequiredService(serviceName: String): S =
    requireNotNull(getService(serviceName)) {
        "Service[$serviceName] not found."
    }

/**
 * Retrieves a required service by Kotlin type, throwing an exception if not found.
 * This method guarantees that a service is returned or an exception is thrown.
 *
 * @param S the type of service to retrieve
 * @param serviceType the Kotlin type to look up
 * @return the service instance of type S
 * @throws IllegalArgumentException if the service is not found
 */
fun <S : Any> ServiceProvider.getRequiredService(serviceType: KType): S =
    requireNotNull(
        getService(serviceType),
    ) {
        "Service[$serviceType] not found."
    }

/**
 * Retrieves a required service by Java class, throwing an exception if not found.
 * This method guarantees that a service is returned or an exception is thrown.
 *
 * @param S the type of service to retrieve
 * @param serviceType the Java class to look up
 * @return the service instance of type S
 * @throws IllegalArgumentException if the service is not found
 */
fun <S : Any> ServiceProvider.getRequiredService(serviceType: Class<S>): S =
    getRequiredService(
        serviceType.kotlin.defaultType,
    )

/**
 * Retrieves a required service using reified type information, throwing an exception if not found.
 * This is a convenience extension that automatically uses the reified type for lookup.
 *
 * @param S the type of service to retrieve
 * @return the service instance of type S
 * @throws IllegalArgumentException if the service is not found
 *
 * @sample
 * ```
 * val provider = SimpleServiceProvider()
 * val service = provider.getRequiredService<MyService>()
 * ```
 */
inline fun <reified S : Any> ServiceProvider.getRequiredService(): S = getRequiredService(typeOf<S>())
