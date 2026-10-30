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

package me.ahoo.wow.query.backend

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort

/**
 * A cursor's effective sort: this sort with each field of [uniqueKey] it does not name appended, in order, as an
 * ascending tie-breaker, so the sort orders records totally. The sort rules (at most
 * [me.ahoo.wow.api.query.Sort.MAX_FIELDS] fields, no field twice) are [QueryResolver]'s, which checks the effective
 * sort.
 */
internal fun List<Sort>.withUniqueSort(uniqueKey: List<QueryField<*>>): List<Sort> {
    val named = mapTo(hashSetOf()) { it.field }
    val missing = uniqueKey.filter { it !in named }
    return if (missing.isEmpty()) this else this + missing.map { Sort(it, Sort.Direction.ASC) }
}

/** [withUniqueSort] with a single [uniqueField]. */
internal fun List<Sort>.withUniqueSort(uniqueField: QueryField<*>): List<Sort> = withUniqueSort(listOf(uniqueField))
