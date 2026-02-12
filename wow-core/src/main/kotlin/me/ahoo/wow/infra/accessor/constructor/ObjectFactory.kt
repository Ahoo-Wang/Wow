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
package me.ahoo.wow.infra.accessor.constructor

import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.getRequiredService
import java.lang.reflect.Constructor
import kotlin.reflect.KParameter
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.kotlinFunction

/**
 * Interface for factories that create new instances of objects.
 * Provides a simple contract for object instantiation without specifying how objects are created.
 *
 * @param T the type of object that will be created
 */
interface ObjectFactory<T : Any> {
    /**
     * Creates a new instance of the object.
     *
     * @return a new instance of type T
     */
    fun newInstance(): T
}

/**
 * ObjectFactory implementation that creates objects using dependency injection.
 * This factory automatically resolves constructor parameters from a ServiceProvider,
 * enabling automatic dependency injection during object instantiation.
 *
 * @param T the type of object that will be created
 * @property constructorAccessor the accessor for the constructor to use
 * @property serviceProvider the service provider for resolving dependencies
 */
class InjectableObjectFactory<T : Any>(
    private val constructorAccessor: ConstructorAccessor<T>,
    private val serviceProvider: ServiceProvider
) : ObjectFactory<T> {
    /**
     * Secondary constructor that creates an InjectableObjectFactory with a Constructor and ServiceProvider.
     * Automatically wraps the constructor in a DefaultConstructorAccessor.
     *
     * @param constructor the Java constructor to use for instantiation
     * @param serviceProvider the service provider for resolving dependencies
     */
    constructor(constructor: Constructor<T>, serviceProvider: ServiceProvider) : this(
        DefaultConstructorAccessor(
            constructor,
        ),
        serviceProvider,
    )

    private val parameters: List<KParameter> = constructorAccessor.constructor.kotlinFunction!!.valueParameters

    /**
     * Creates a new instance by resolving all constructor parameters from the service provider.
     * Each constructor parameter type is looked up in the service provider, and the resolved
     * services are passed as arguments to the constructor.
     *
     * @return a new instance of type T with dependencies injected
     * @throws IllegalArgumentException if any required service is not found in the provider
     *
     * Example usage:
     * ```
     * val constructor = MyClass::class.java.getConstructor(Dependency::class.java)
     * val factory = InjectableObjectFactory(constructor, serviceProvider)
     * val instance = factory.newInstance() // automatically injects Dependency
     * ```
     */
    override fun newInstance(): T {
        val args = parameters
            .map {
                serviceProvider.getRequiredService<Any>(it.type)
            }.toTypedArray()
        return constructorAccessor.invoke(args)
    }
}
