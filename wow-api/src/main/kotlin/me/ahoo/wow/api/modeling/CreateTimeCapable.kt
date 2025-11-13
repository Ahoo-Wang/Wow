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

/**
 * Interface for entities that track their creation time.
 *
 * This interface provides a standard way to access the timestamp when an entity was created,
 * typically represented as milliseconds since the Unix epoch (January 1, 1970, 00:00:00 UTC).
 */
interface CreateTimeCapable {
    /**
     * The creation time of the entity in milliseconds since the Unix epoch.
     *
     * @return The timestamp when this entity was created.
     */
    val createTime: Long
}
