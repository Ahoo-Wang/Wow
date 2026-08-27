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
package me.ahoo.wow.api.naming

/**
 * Interface for entities that belong to a named bounded context.
 *
 * In Domain-Driven Design, a bounded context represents a boundary within which
 * a particular domain model applies. This interface allows entities to declare
 * their association with a specific bounded context by name.
 */
interface NamedBoundedContext {
    /**
     * The name of the bounded context this entity belongs to.
     *
     * @return A string representing the bounded context name.
     */
    val contextName: String

    /**
     * Checks if this entity belongs to the same bounded context as another entity.
     *
     * @param other The other NamedBoundedContext to compare with.
     * @return true if both entities belong to the same bounded context, false otherwise.
     */
    fun isSameBoundedContext(other: NamedBoundedContext): Boolean = contextName == other.contextName
}
