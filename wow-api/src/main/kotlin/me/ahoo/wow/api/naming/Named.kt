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
 * Interface for entities that have a name identifier.
 *
 * This interface provides a standard way for objects to expose a human-readable
 * name that can be used for identification, display, or referencing purposes.
 */
interface Named {
    /**
     * The name of the entity.
     *
     * @return A string representing the name of the entity.
     */
    val name: String
}
