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

// Not Wow source. Everything that looks like a declaration here is inside a
// comment or a string: as far as the checker should see, this checkout
// declares no SearchMode, no AggregationGroup, and no rule.
package me.ahoo.wow.api.query

/**
 * Usage, for readers:
 *
 * ```
 * enum class SearchMode { TERMS }
 *
 * @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
 * @JsonSubTypes(JsonSubTypes.Type(A::class, name = "TERMS"))
 * sealed interface AggregationGroup
 *
 * require(query.isNotBlank()) { "Commented rule." }
 * ```
 */
object Documented {
    const val EXAMPLE = "enum class SearchMode { PHRASE }"
}
