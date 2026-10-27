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

package me.ahoo.wow.manifest

/**
 * The names of the query field constants of an aggregate's state: the compiler's
 * `<State>Properties` in the domain module and the `<Context>Contract` generated into its api module from the state
 * schema name each path of the state alike, so the two sets are the same.
 *
 * A path is what Jackson writes and the state schema describes, `state.<property>` with nested properties after dots,
 * each segment a JSON property name ([isSegment]); its constant is the path's segments in upper snake case, joined by
 * `__`: `state.items.productId` is `STATE__ITEMS__PRODUCT_ID`.
 */
object QueryFieldNames {
    /** The first segment of every state path: a snapshot holds the state under `state`. */
    const val STATE: String = "state"

    private val SEGMENT = Regex("^[A-Za-z_][A-Za-z0-9_-]*$")
    private val LOWER_UPPER = Regex("([a-z0-9])([A-Z])")
    private val ACRONYM_WORD = Regex("([A-Z]+)([A-Z][a-z])")
    private val NOT_IDENTIFIER = Regex("[^A-Za-z0-9_]")

    /** Whether a query names the JSON property [name] as a segment of a path: an identifier, `-` allowed. */
    fun isSegment(name: String): Boolean = SEGMENT.matches(name)

    /** The constant of [path], a dotted path: `state.items.productId` as `STATE__ITEMS__PRODUCT_ID`. */
    fun constantOf(path: String): String = path.split('.').joinToString("__", transform = ::upperSnake)

    /** `productId` as `PRODUCT_ID`, `URLValue` as `URL_VALUE`; a character no identifier takes becomes `_`. */
    fun upperSnake(name: String): String =
        name.replace(LOWER_UPPER, "$1_$2")
            .replace(ACRONYM_WORD, "$1_$2")
            .replace(NOT_IDENTIFIER, "_")
            .uppercase()
}
