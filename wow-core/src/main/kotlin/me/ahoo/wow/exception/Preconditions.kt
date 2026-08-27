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

package me.ahoo.wow.exception

/**
 * Utility object for checking preconditions and throwing standardized exceptions.
 *
 * This object provides methods for validating conditions and throwing WowException
 * with specific error codes when conditions are not met. It uses lazy evaluation
 * for error messages to avoid unnecessary string construction.
 *
 * @see WowException
 */
object Preconditions {
    /**
     * Checks a boolean condition and throws WowException if false.
     *
     * This method validates that the given condition is true. If the condition
     * evaluates to false, it throws a WowException with the specified error code
     * and message.
     *
     * Example usage:
     * ```kotlin
     * Preconditions.check(userId.isNotBlank(), ErrorCodes.ILLEGAL_ARGUMENT) {
     *     "User ID cannot be blank"
     * }
     * ```
     *
     * @param value the condition to check
     * @param errorCode the error code to use if the condition fails
     * @param lazyMessage a lazy function that provides the error message
     * @throws WowException if the condition is false
     */
    inline fun check(
        value: Boolean,
        errorCode: String,
        lazyMessage: () -> String = { "" }
    ) {
        if (!value) {
            val message = lazyMessage()
            throw WowException(errorCode, message)
        }
    }
}
