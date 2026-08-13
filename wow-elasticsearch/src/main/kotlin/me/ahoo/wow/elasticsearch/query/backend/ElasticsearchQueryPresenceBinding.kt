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

@file:JvmSynthetic

package me.ahoo.wow.elasticsearch.query.backend

import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
import me.ahoo.wow.query.schema.QueryFieldValueKind

internal class ElasticsearchQueryPresenceBinding(
    private val fields: ElasticsearchQueryFieldBinding,
) {
    fun present(logical: LogicalField): PresenceTerm = term(logical, ElasticsearchQueryPresenceEncoder.PRESENT)

    fun explicitNull(logical: LogicalField): PresenceTerm = term(logical, ElasticsearchQueryPresenceEncoder.NULL)

    private fun term(logical: LogicalField, kind: String): PresenceTerm {
        val source = fields.source(logical)
        val segments = logical.value.split('.')
        var parentPath: String? = null
        for (endIndex in 1 until segments.size) {
            val ancestor = LogicalField(segments.take(endIndex).joinToString("."))
            if (fields.contains(ancestor) && fields.schema(ancestor).valueKind == QueryFieldValueKind.OBJECT) {
                val ancestorSource = fields.source(ancestor)
                if (source.startsWith("$ancestorSource.")) {
                    parentPath = ancestorSource
                }
            }
        }
        val directName = if (parentPath == null) source.substringAfterLast('.') else source.removePrefix("$parentPath.")
        require('.' !in directName) { "Elasticsearch presence field is not a direct object child." }
        val metadataPath = listOfNotNull(parentPath, ElasticsearchQueryPresenceEncoder.NAMESPACE, kind)
            .joinToString(".")
        return PresenceTerm(metadataPath, directName)
    }
}

internal data class PresenceTerm(val field: String, val directName: String)
