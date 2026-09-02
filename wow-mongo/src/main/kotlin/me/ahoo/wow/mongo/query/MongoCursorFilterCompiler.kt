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

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Sort
import org.bson.Document
import org.bson.conversions.Bson

internal object MongoCursorFilterCompiler {
    fun compile(sort: List<Sort>, values: List<Any?>): Bson {
        require(sort.size == values.size) { "Cursor values must match effective sort fields." }
        require(sort.size <= AggregationQuery.MAX_SORT_FIELDS) {
            "Cursor sort must contain at most ${AggregationQuery.MAX_SORT_FIELDS} fields."
        }
        require(values.all(Any?::isMongoCursorScalar)) { "Cursor values must be BSON scalar values." }
        return Filters.or(
            sort.indices.map { index ->
                Filters.and(
                    buildList {
                        repeat(index) { equalIndex ->
                            add(Filters.eq(sort[equalIndex].field.path, values[equalIndex]))
                        }
                        add(after(sort[index], values[index]))
                    },
                )
            },
        )
    }

    private fun after(sort: Sort, value: Any?): Bson = when {
        value == null && sort.direction == Sort.Direction.ASC -> Filters.ne(sort.field.path, null)
        value == null -> Document("\$expr", false)
        sort.direction == Sort.Direction.ASC -> Filters.gt(sort.field.path, value)
        else -> Filters.or(Filters.lt(sort.field.path, value), Filters.eq(sort.field.path, null))
    }
}
