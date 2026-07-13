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

package me.ahoo.wow.bi

sealed interface ClickHouseTopology {
    data object Standalone : ClickHouseTopology

    data class Cluster(
        val name: String = "{cluster}",
        val installation: String = "{installation}",
    ) : ClickHouseTopology {
        init {
            name.requireValidTopologyValue("name", MAX_VALUE_LENGTH)
            installation.requireValidTopologyValue("installation", MAX_VALUE_LENGTH)
        }

        private fun String.requireValidTopologyValue(name: String, maxLength: Int) {
            require(isNotBlank()) { "$name must not be blank" }
            require(none { it == '\u0000' || it.isISOControl() }) {
                "$name must not contain control characters"
            }
            require(length <= maxLength) {
                "$name length $length must be less than or equal to $maxLength"
            }
        }

        companion object {
            const val MAX_VALUE_LENGTH: Int = 128
        }
    }
}
