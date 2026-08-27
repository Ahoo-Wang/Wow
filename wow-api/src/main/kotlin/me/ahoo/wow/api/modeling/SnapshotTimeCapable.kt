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
 * Interface for entities that track when their state was last snapshotted.
 *
 * This interface provides access to the timestamp when the aggregate's state was
 * captured as a snapshot, which is useful for optimization in event-sourced systems.
 */
interface SnapshotTimeCapable {
    /**
     * The timestamp when this aggregate's state was last snapshotted, in milliseconds since the Unix epoch.
     *
     * @return The snapshot timestamp as a long value.
     */
    @get:Schema(description = "The snapshot time of the aggregate, represented as a Unix timestamp in milliseconds.")
    val snapshotTime: Long
}
