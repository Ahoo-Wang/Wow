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
 * ABAC tag key, such as `dept`, `role`, or `level`.
 */
typealias AbacTagKey = String

/**
 * Values associated with an ABAC tag key.
 *
 * Multiple values support many-to-many matches, such as membership in both `eng` and `pm` departments.
 */
typealias AbacTagValue = List<String>

/**
 * ABAC tags grouped by key.
 *
 * Each key can contain multiple values.
 *
 * **Examples:**
 * ```
 * // A user in two departments with an administrator role.
 * mapOf(
 *     "dept" to listOf("eng", "pm"),
 *     "role" to listOf("admin")
 * )
 *
 * // A document available only to the engineering department.
 * mapOf(
 *     "dept" to listOf("eng")
 * )
 *
 * // No tags represents a public resource.
 * emptyMap()
 * ```
 *
 * **Empty values:**
 * - A blank key is invalid.
 * - An empty value list represents no tag for that key.
 * - Filter invalid entries when constructing tags:
 *   ```
 *   tags.filter { it.key.isNotBlank() && it.value.isNotEmpty() }
 *   ```
 *
 * **Wildcard:**
 * - `["*"]` matches every value for a key. For example,
 *   `mapOf("dept" to listOf("*"))` matches resources from every department.
 */
typealias AbacTags = Map<AbacTagKey, AbacTagValue>

/**
 * Exposes ABAC tags for a principal or resource.
 *
 * Attribute-Based Access Control evaluates tags on principals and protected resources
 * to make authorization decisions.
 */
interface AbacTaggable {
    /**
     * Tags keyed by attribute name.
     */
    val tags: AbacTags
}

/**
 * Extracts ABAC tags from a source object.
 *
 * @see AbacTaggable
 */
interface AbacTagsExtractor<in SOURCE : Any> {
    /**
     * Extracts tags from [source].
     */
    fun extract(source: SOURCE): AbacTags
}

val EMPTY_ABAC_TAGS = emptyMap<AbacTagKey, AbacTagValue>()

/**
 * Whether this value list contains the wildcard value.
 *
 * A wildcard allows the tag key to match any value and maps to [Operator.EXISTS]
 * when building a query condition.
 *
 * @see Operator.EXISTS
 */
val AbacTagValue.wildcard: Boolean
    get() = this.contains("*")
