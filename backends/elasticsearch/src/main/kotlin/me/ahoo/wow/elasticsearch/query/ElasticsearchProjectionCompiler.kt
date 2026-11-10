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
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.backend.RecordProjection
import tools.jackson.databind.node.ObjectNode

/**
 * A query's projection in two steps, as the MongoDB backend projects one.
 * Elasticsearch returns a superset of what the projection keeps ([compile]): whole subtrees of at most two segments
 * (`state.lines`, `access.region`), which never cross an array of the state, so no element is dropped. The record read
 * from it is then projected exactly ([project]) by [RecordProjection], the reference semantics: a source filter alone
 * drops an element of an array that holds none of the projected leaf, shifting the others' positions.
 */
internal object ElasticsearchProjectionCompiler {
    private const val NATIVE_DEPTH = 2

    /** The source filter of an empty projection: immutable, so built once. */
    private val EMPTY: SourceFilter = SourceFilter.of { it.includes(emptyList()).excludes(emptyList()) }

    /** The native source filter of [projection]: the superset of what it keeps. */
    fun compile(projection: Projection, admitted: AdmittedQuery<*>): SourceFilter {
        if (projection.include.isEmpty() && projection.exclude.isEmpty()) return EMPTY
        val includes = projection.include.map { subtree(admitted.physicalPath(it)) }
        val kept = includes.filterNot { path -> includes.any { path.startsWith("$it.") } }.distinct()
        val excludes = if (kept.isNotEmpty()) {
            emptyList()
        } else {
            val paths = projection.exclude.map(admitted::physicalPath)
            paths.filter { path -> path.count { it == '.' } < NATIVE_DEPTH }
        }
        return SourceFilter.of { it.includes(kept.toSourceFields()).excludes(excludes.toSourceFields()) }
    }

    /** [record], read through [compile]'s source filter, with only what [admitted]'s projection keeps. */
    fun project(admitted: AdmittedQuery<Queryable<*>>, record: ObjectNode): ObjectNode {
        val projection = admitted.query.projection
        fun paths(fields: List<QueryField<*>>) = fields.map { admitted.field(it).logicalField.path.split('.') }
        return RecordProjection.project(record, paths(projection.include), paths(projection.exclude))
    }

    private fun subtree(path: String): String = path.split('.').take(NATIVE_DEPTH).joinToString(".")

    private fun List<String>.toSourceFields(): List<String> = flatMap { path -> listOf(path, "$path.*") }.distinct()
}
