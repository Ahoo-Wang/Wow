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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.FieldSort
import co.elastic.clients.elasticsearch._types.SortOptions
import co.elastic.clients.elasticsearch._types.SortOrder
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.serialization.MessageRecords

object ElasticsearchSortCompiler {
    fun compile(sort: List<Sort>, schema: QueryModelSchema): List<SortOptions> = compilePhysical(
        sort.map { it.copy(field = schema.resolvePhysicalField(it.field, QueryCapability.SORT)) },
    )

    internal fun compileCursor(sort: List<Sort>, schema: QueryModelSchema): List<SortOptions> = compilePhysical(
        sort.map { it.copy(field = schema.resolvePhysicalField(it.field, QueryCapability.SORT)) },
    ) { logicalSort ->
        missing(if (logicalSort.direction == Sort.Direction.ASC) "_first" else "_last")
    }

    internal fun compilePhysical(sort: List<Sort>): List<SortOptions> = compilePhysical(sort) { }

    private inline fun compilePhysical(
        sort: List<Sort>,
        crossinline configure: FieldSort.Builder.(Sort) -> Unit,
    ): List<SortOptions> {
        return sort.map {
            SortOptions.of { sortBuilder ->
                sortBuilder.field { fieldBuilder ->
                    fieldBuilder.field(it.field.path).order(it.direction.toSortOrder())
                    fieldBuilder.configure(it)
                    if (it.field.path.startsWith("${MessageRecords.BODY}.")) {
                        fieldBuilder.nested { nested -> nested.path(MessageRecords.BODY) }
                    }
                    fieldBuilder
                }
            }
        }
    }

    fun Sort.Direction.toSortOrder(): SortOrder {
        return when (this) {
            Sort.Direction.ASC -> SortOrder.Asc
            Sort.Direction.DESC -> SortOrder.Desc
        }
    }
}
