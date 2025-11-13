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
 * Interface for identifying the owner of a resource.
 */
interface OwnerId {
    /**
     * The unique identifier of the resource owner.
     */
    val ownerId: String

    /**
     * Companion object containing constants and utility functions related to the interface.
     */
    companion object {
        /**
         * The default resource owner identifier.
         */
        const val DEFAULT_OWNER_ID = ""

        /**
         * Extension function to handle nullable String values.
         * Returns the default owner ID if the string is null.
         *
         * @return The string itself if not null, otherwise DEFAULT_OWNER_ID.
         */
        fun String?.orDefaultOwnerId(): String = this ?: DEFAULT_OWNER_ID
    }
}
