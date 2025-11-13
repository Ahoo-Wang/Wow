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

import me.ahoo.wow.infra.accessor.ensureAccessible
import java.lang.reflect.Constructor

/**
 * Default implementation of ConstructorAccessor that provides basic constructor invocation capabilities.
 * This class automatically ensures the constructor is accessible during initialization,
 * making it ready for reflection-based instantiation of private, protected, or package-private constructors.
 *
 * @param T the type of object that will be created by the constructor
 * @property constructor the Java constructor to be accessed
 */
class DefaultConstructorAccessor<T : Any>(
    override val constructor: Constructor<T>
) : ConstructorAccessor<T> {
    /**
     * Initialization block that ensures the constructor is accessible for reflection.
     * This automatically makes private, protected, or package-private constructors accessible.
     */
    init {
        constructor.ensureAccessible()
    }
}
