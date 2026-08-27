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
 * Interface for objects that can materialize their state into a different representation.
 *
 * This interface provides a contract for transforming the current state of an object
 * into a materialized form, which could be a different data structure, format, or view
 * suitable for storage, transmission, or presentation.
 *
 * @param MATERIALIZED The type of the materialized representation.
 */
interface MaterializeState<MATERIALIZED> {
    /**
     * Materializes the current state into the target representation.
     *
     * @return The materialized representation of the current state.
     */
    fun materialize(): MATERIALIZED
}
