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
 * Interface for entities that track the ID of their last processed event.
 *
 * This interface is used in event-sourced systems to maintain the identity of the most
 * recent event that has been applied to an aggregate's state.
 */
interface EventIdCapable {
    /**
     * The unique identifier of the last event processed by this aggregate.
     *
     * @return The event ID as a string.
     */
    @get:Schema(description = "The event id of the aggregate.")
    val eventId: String
}
