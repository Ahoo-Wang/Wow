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

package me.ahoo.wow.api.query.dsl

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort

/**
 * Builds a sort: each call adds one field, in order.
 *
 * ```kotlin
 * sort {
 *     "state.price".asc()
 *     OrderStateProperties.LAST_EVENT_TIME.desc()
 * }
 * ```
 */
@QueryDslMarker
class SortDsl : NestedFieldDsl() {
    private val sorts: MutableList<Sort> = mutableListOf()

    fun sort(sort: Sort) {
        sorts.add(sort)
    }

    fun String.asc() = sort(Sort(QueryField(withNestedField()), Sort.Direction.ASC))

    fun String.desc() = sort(Sort(QueryField(withNestedField()), Sort.Direction.DESC))

    fun QueryField<*>.asc() = path.asc()

    fun QueryField<*>.desc() = path.desc()

    fun build(): List<Sort> = sorts.toList()
}
