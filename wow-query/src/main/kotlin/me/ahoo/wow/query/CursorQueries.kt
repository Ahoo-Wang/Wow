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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.Sort

internal val FORBIDDEN_CURSOR_SORTS = setOf("_score", "_doc", "_shard_doc")

fun ICursorQuery.withUniqueSort(uniqueField: String): ICursorQuery {
    val effective = if (sort.any { it.field == uniqueField }) {
        sort
    } else {
        sort + Sort(uniqueField, Sort.Direction.ASC)
    }
    val fields = effective.map(Sort::field)
    require(fields.distinct().size == fields.size) { "Cursor sort fields must be unique." }
    require(fields.none(FORBIDDEN_CURSOR_SORTS::contains)) {
        "Cursor sort contains an unstable metadata field."
    }
    require(effective.size <= AggregationQuery.MAX_SORT_FIELDS) {
        "Effective cursor sort must contain at most ${AggregationQuery.MAX_SORT_FIELDS} fields."
    }
    return CursorQuery(filter, projection, effective, size, cursor)
}
