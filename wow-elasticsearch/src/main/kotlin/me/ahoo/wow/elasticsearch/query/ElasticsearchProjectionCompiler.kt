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

import co.elastic.clients.elasticsearch.core.search.SourceFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.AdmittedQuery

object ElasticsearchProjectionCompiler {
    /** The source filter of an empty projection: immutable, so built once. */
    private val EMPTY: SourceFilter = SourceFilter.of { it.includes(emptyList()).excludes(emptyList()) }

    fun compile(projection: Projection, admitted: AdmittedQuery<*>): SourceFilter =
        if (projection.include.isEmpty() && projection.exclude.isEmpty()) {
            EMPTY
        } else {
            SourceFilter.of {
                it.includes(projection.include.toSourceFields(admitted))
                it.excludes(projection.exclude.toSourceFields(admitted))
            }
        }

    private fun List<QueryField>.toSourceFields(admitted: AdmittedQuery<*>): List<String> =
        flatMap { field ->
            val path = admitted.field(field).physicalField.path
            listOf(path, "$path.*")
        }.distinct()
}
