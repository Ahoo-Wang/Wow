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

package me.ahoo.wow.api.abac

/**
 * Merges two ABAC tag maps.
 *
 * Values for the same key are combined as an ordered union. Keys present in only one
 * map retain all of their values.
 *
 * **Example:**
 * ```
 * val tags1 = mapOf("dept" to listOf("eng"), "role" to listOf("admin"))
 * val tags2 = mapOf("dept" to listOf("pm"), "team" to listOf("backend"))
 *
 * tags1.merge(tags2)
 * // Result: { "dept": ["eng", "pm"], "role": ["admin"], "team": ["backend"] }
 * ```
 *
 * @param other tags to merge into this map
 * @return a new map containing the merged tags
 */
fun AbacTags.merge(other: AbacTags): AbacTags {
    if (other.isEmpty()) {
        return this
    }
    if (isEmpty()) {
        return other
    }
    return this.keys.union(other.keys).associateWith { key ->
        (this[key].orEmpty() + other[key].orEmpty()).distinct()
    }
}
