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

package me.ahoo.wow.infra

/**
 * Executes a function on a string if it is not null, empty, or blank (contains only whitespace).
 * This is a null-safe utility function that provides a concise way to perform operations
 * on non-blank strings while returning null for blank inputs.
 *
 * @param R the return type of the function
 * @param func the function to execute if the string is not blank
 * @return the result of the function if the string is not blank, null otherwise
 *
 * Example usage:
 * ```
 * val result = "  hello  ".ifNotBlank { it.trim().uppercase() } // returns "HELLO"
 * val nullResult = "   ".ifNotBlank { it.trim() } // returns null
 * val nullInput = null.ifNotBlank { it.length } // returns null
 * ```
 */
public inline fun <R> String?.ifNotBlank(func: (String) -> R): R? {
    if (this.isNullOrBlank()) {
        return null
    }
    return func(this)
}
