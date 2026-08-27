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

package me.ahoo.wow.query.dsl

import me.ahoo.wow.api.query.Sort

/**
 * A DSL for constructing a list of [Sort] objects, allowing for the definition of sorting criteria
 * in a fluent and readable manner. This class extends [NestedFieldDsl], enabling the use of nested fields
 * within the sorting context.
 *
 * Usage:
 * ```
 * val sorts = sort {
 *     "name".asc()
 *     "age".desc()
 * }
 * ```
 *
 * @see NestedFieldDsl
 * @see Sort
 */
@QueryDslMarker
class SortDsl : NestedFieldDsl() {

    private val sorts: MutableList<Sort> = mutableListOf()

    fun sort(sort: Sort) {
        sorts.add(sort)
    }

    fun String.asc() {
        sort(Sort(this.withNestedField(), Sort.Direction.ASC))
    }

    fun String.desc() {
        sort(Sort(this.withNestedField(), Sort.Direction.DESC))
    }

    fun build(): List<Sort> {
        return sorts.toList()
    }
}
