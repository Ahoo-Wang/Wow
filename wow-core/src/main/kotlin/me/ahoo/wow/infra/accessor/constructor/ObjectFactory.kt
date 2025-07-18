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

interface ObjectFactory<T : Any> {
    fun newInstance(): T
}

class InjectableObjectFactory<T : Any>(
    private val constructorAccessor: ConstructorAccessor<T>,
    private val serviceProvider: ServiceProvider
) : ObjectFactory<T> {

    constructor(constructor: Constructor<T>, serviceProvider: ServiceProvider) : this(
        DefaultConstructorAccessor(
            constructor,
        ),
        serviceProvider,
    )

    override fun newInstance(): T {
        val args = constructorAccessor
            .constructor
            .parameterTypes
            .map {
                serviceProvider.getRequiredService(it)
            }.toTypedArray()
        return constructorAccessor.invoke(args)
    }
}
