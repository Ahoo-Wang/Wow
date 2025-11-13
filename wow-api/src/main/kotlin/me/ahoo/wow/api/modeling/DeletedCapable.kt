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

package me.ahoo.wow.api.modeling

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Interface for entities that can be marked as deleted (soft deletion).
 *
 * This interface provides a standard way to track whether an entity has been logically
 * deleted without actually removing it from the data store. Soft deletion allows for
 * audit trails and potential recovery of deleted data.
 */
interface DeletedCapable {
    /**
     * Indicates whether the entity has been marked as deleted.
     *
     * @return true if the entity is deleted, false if it is active.
     */
    @get:Schema(description = "Whether the aggregate is deleted.")
    val deleted: Boolean
}
