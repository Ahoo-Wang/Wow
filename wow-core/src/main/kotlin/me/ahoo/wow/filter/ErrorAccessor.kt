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

package me.ahoo.wow.filter

/**
 * Interface for accessing and managing error information in a context.
 *
 * This interface provides basic operations for setting, getting, and clearing error objects,
 * allowing components to track and handle errors during processing.
 */
interface ErrorAccessor {
    /**
     * Sets the error object for this context.
     *
     * @param throwable the error to set, of type [Throwable]
     */
    fun setError(throwable: Throwable)

    /**
     * Gets the current error object.
     *
     * @return the current error object if present, null otherwise
     */
    fun getError(): Throwable?

    /**
     * Clears the current error object.
     *
     * After calling this method, [getError] should return null.
     */
    fun clearError()
}
