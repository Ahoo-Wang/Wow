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

import co.elastic.clients.elasticsearch._types.SortOptions
import co.elastic.clients.elasticsearch._types.SortOrder
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.ResolvedField
import me.ahoo.wow.query.schema.QueryViolation

object ElasticsearchSortCompiler {
    /** Metadata sorts (`_score`, `_doc`, `_shard_doc`) keep Elasticsearch's own missing policy. */
    fun compile(sort: List<Sort>, admitted: AdmittedQuery<*>): List<SortOptions> =
        compilePhysical(sort, admitted, missingOnMetadata = false)

    internal fun compileCursor(sort: List<Sort>, admitted: AdmittedQuery<*>): List<SortOptions> {
        sort.firstOrNull { admitted.field(it.field).physicalField in METADATA_SORT_FIELDS }?.let {
            throw QueryViolation.CursorNotAllowed(admitted.field(it.field).logicalField).rejection()
        }
        return compilePhysical(sort, admitted, missingOnMetadata = true)
    }

    /**
     * Each sort at its admitted physical field, missing values first ascending and last descending, and through the
     * nested mapping its binding lies in ([ResolvedField.physicalScope]).
     */
    private fun compilePhysical(
        sort: List<Sort>,
        admitted: AdmittedQuery<*>,
        missingOnMetadata: Boolean,
    ): List<SortOptions> = sort.map {
        val resolved = admitted.field(it.field)
        SortOptions.of { sortBuilder ->
            sortBuilder.field { fieldBuilder ->
                fieldBuilder.field(admitted.physicalPath(it.field)).order(it.direction.toSortOrder())
                if (missingOnMetadata || resolved.physicalField !in METADATA_SORT_FIELDS) {
                    fieldBuilder.missing(if (it.direction == Sort.Direction.ASC) "_first" else "_last")
                }
                resolved.physicalScope?.let { scope -> fieldBuilder.nested { nested -> nested.path(scope.path) } }
                fieldBuilder
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

private val METADATA_SORT_FIELDS = setOf(
    QueryField("_score"),
    QueryField("_doc"),
    QueryField("_shard_doc"),
)
