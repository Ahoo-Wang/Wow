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

package me.ahoo.wow.test.dsl

/**
 * Interface for specifying names in test specifications.
 *
 * Implementations of this interface allow setting descriptive names for test elements,
 * which can be used for better test organization and reporting.
 */
interface NameSpecCapable {
    /**
     * Sets the name for this test element.
     *
     * @param name The descriptive name to assign to the test element.
     */
    fun name(name: String)

    companion object {
        /**
         * Appends a formatted name to a [StringBuilder] if the name is not blank.
         *
         * This utility function formats the name by wrapping it in parentheses
         * and appends it to the provided StringBuilder, useful for building
         * descriptive test names or labels.
         *
         * @param name The name to append. If blank, nothing is appended.
         */
        fun StringBuilder.appendName(name: String) {
            if (name.isBlank()) {
                return
            }
            append("($name)")
        }
    }
}
