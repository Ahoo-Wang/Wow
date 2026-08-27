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

package me.ahoo.wow.api.query

/**
 * Enumeration representing the possible states of deletion for entities in the system.
 *
 * This enum is used to filter and categorize entities based on their deletion status,
 * allowing queries to target active, deleted, or all entities regardless of deletion state.
 */
enum class DeletionState {
    /**
     * Represents entities that are currently active and not deleted.
     * These entities are fully functional and visible in normal operations.
     */
    ACTIVE,

    /**
     * Represents entities that have been marked as deleted.
     * These entities may be retained for audit purposes but are typically excluded from normal queries.
     */
    DELETED,

    /**
     * Represents all entities regardless of their deletion state.
     * This value is used when queries need to include both active and deleted entities.
     */
    ALL
}
