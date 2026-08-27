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
 * Interface for entities that maintain a state of a specific type.
 *
 * This interface provides access to the current state of an aggregate or entity,
 * where the state represents the business data and can be of any type.
 *
 * @param S The type of the state data.
 */
interface StateCapable<S : Any> {
    /**
     * The current state of the aggregate.
     *
     * @return The state object of type S.
     */
    @get:Schema(description = "The state of the aggregate.")
    val state: S
}
