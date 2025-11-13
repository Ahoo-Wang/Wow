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
import kotlin.reflect.KType
import kotlin.reflect.full.defaultType
import kotlin.reflect.full.isSubtypeOf

/**
 * Simple implementation of ServiceProvider using thread-safe ConcurrentHashMap for storage.
 * Provides basic service registration and retrieval functionality with support for both
 * type-based and name-based lookups. Supports subtype matching for type-based lookups.
 *
 * This implementation is suitable for most use cases requiring a lightweight,
 * thread-safe service provider.
 */
class SimpleServiceProvider : ServiceProvider {
    /**
     * Thread-safe map storing services keyed by their Kotlin type.
     * Used for type-based service lookups.
     */
    private val typedServices: ConcurrentHashMap<KType, Any> = ConcurrentHashMap<KType, Any>()

    /**
     * Thread-safe map storing services keyed by their registered name.
     * Used for name-based service lookups.
     */
    private val namedServices: ConcurrentHashMap<String, TypedService> = ConcurrentHashMap<String, TypedService>()

    /**
     * Set of all registered service names.
     * Returns a view of the keys in the namedServices map.
     */
    override val serviceNames: Set<String>
        get() = namedServices.keys

    /**
     * Registers a service with the provider.
     * Stores the service in both typed and named service maps for efficient lookup.
     *
     * @param service the service instance to register
     * @param serviceName the name to register the service under
     * @param serviceType the Kotlin type of the service
     */
    override fun register(
        service: Any,
        serviceName: String,
        serviceType: KType
    ) {
        typedServices[serviceType] = service
        namedServices[serviceName] = TypedService(serviceType, service)
    }

    /**
     * Retrieves a service by its Kotlin type.
     * First tries exact type match, then falls back to subtype matching if no exact match is found.
     *
     * @param S the type of service to retrieve
     * @param serviceType the Kotlin type to look up
     * @return the service instance of type S, or null if not found
     */
    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> getService(serviceType: KType): S? {
        val service = typedServices[serviceType] as S?
        if (service != null) {
            return service
        }
        return typedServices.values.firstOrNull {
            val instanceType = it.javaClass.kotlin.defaultType
            instanceType.isSubtypeOf(serviceType)
        } as S?
    }

    /**
     * Retrieves a service by its registered name.
     * Performs a direct lookup in the named services map.
     *
     * @param S the type of service to retrieve
     * @param serviceName the name of the service to look up
     * @return the service instance of type S, or null if not found
     */
    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> getService(serviceName: String): S? = namedServices[serviceName]?.service as S?

    /**
     * Creates a shallow copy of this service provider.
     * The copy contains references to the same service instances but in separate maps.
     *
     * @return a new SimpleServiceProvider instance with copied service registrations
     */
    override fun copy(): SimpleServiceProvider {
        val copy = SimpleServiceProvider()
        copy.typedServices.putAll(typedServices)
        copy.namedServices.putAll(namedServices)
        return copy
    }

    /**
     * Copies all registered services from this provider to the target provider.
     * Iterates through all named services and registers them with the target provider.
     *
     * @param target the provider to copy services to
     */
    override fun copyTo(target: ServiceProvider) {
        namedServices.forEach { (serviceName, typedService) ->
            target.register(service = typedService.service, serviceName = serviceName, serviceType = typedService.type)
        }
    }

    /**
     * Internal data class representing a typed service registration.
     * Used to store both the service type and instance together for efficient copying operations.
     *
     * @property type the Kotlin type of the service
     * @property service the service instance
     */
    internal class TypedService(
        val type: KType,
        val service: Any
    )
}
