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
package me.ahoo.wow.api

import com.fasterxml.jackson.annotation.JsonIgnore

/**
 * Represents an entity that maintains version information for optimistic concurrency control and state tracking.
 *
 * This interface provides a standardized way to handle versioning across different domain objects,
 * ensuring that changes can be tracked and conflicts can be detected when multiple operations
 * attempt to modify the same entity simultaneously.
 *
 * The version starts at [UNINITIALIZED_VERSION] for new entities and increments with each modification.
 * The first meaningful version after initialization is [INITIAL_VERSION].
 *
 * @author ahoo wang
 *
 */
interface Version {
    companion object {
        /**
         * The version value representing an uninitialized entity.
         * New entities start with this version before their first modification.
         */
        const val UNINITIALIZED_VERSION = 0

        /**
         * The version value representing the first initialized state of an entity.
         * This is the version assigned after the entity's initial creation or first meaningful operation.
         */
        const val INITIAL_VERSION = 1
    }

    /**
     * The current version number of the entity.
     *
     * Versions are monotonically increasing and start from [INITIAL_VERSION] for initialized entities.
     * [UNINITIALIZED_VERSION] indicates an uninitialized state.
     */
    val version: Int

    /**
     * Whether the entity has been initialized.
     */
    @get:JsonIgnore
    val initialized: Boolean
        get() {
            return version > UNINITIALIZED_VERSION
        }

    /**
     * Whether the entity is at its initial version.
     */
    @get:JsonIgnore
    val isInitialVersion: Boolean
        get() {
            return version == INITIAL_VERSION
        }
}
