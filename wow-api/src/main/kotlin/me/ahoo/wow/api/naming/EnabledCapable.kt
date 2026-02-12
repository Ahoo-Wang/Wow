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
 * Interface for entities that can be enabled or disabled.
 *
 * This interface provides a standard way to control whether an entity is active
 * and available for use, or inactive and unavailable.
 */
interface EnabledCapable {
    /**
     * Indicates whether the entity is enabled and available for use.
     *
     * @return true if the entity is enabled, false if disabled.
     */
    val enabled: Boolean
}
