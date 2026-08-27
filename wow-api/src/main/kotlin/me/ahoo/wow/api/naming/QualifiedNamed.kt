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
 * Interface for entities that have a fully qualified name.
 *
 * A qualified name provides a complete, unambiguous identifier that includes
 * all necessary context (such as namespace, path, or hierarchy) to uniquely
 * identify the entity across different scopes or systems.
 */
interface QualifiedNamed {
    /**
     * The fully qualified name that uniquely identifies this entity.
     *
     * The qualified name should include all necessary context to distinguish
     * this entity from others with similar names in different scopes.
     *
     * @return A string representing the fully qualified name.
     */
    val qualifiedName: String
}
