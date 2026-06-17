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

package me.ahoo.wow.schema.openapi

import com.fasterxml.classmate.ResolvedType
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.schema.openapi.SchemaMerger.mergeTo
import tools.jackson.databind.node.ObjectNode

internal class SchemaReferenceRegistry(
    private val schemaConverter: OpenAPISchemaConverter
) {
    private val references: MutableList<SchemaReference> = mutableListOf()

    fun track(type: ResolvedType, node: ObjectNode): Schema<*> {
        val reference = SchemaReference(type, schemaConverter.toSchema(node), node)
        references.add(reference)
        return reference.schema
    }

    fun mergeAll() {
        references.forEach {
            it.merge(schemaConverter)
        }
    }

    private class SchemaReference(
        val type: ResolvedType,
        val schema: Schema<*>,
        val node: ObjectNode
    ) {
        fun merge(schemaConverter: OpenAPISchemaConverter) {
            schemaConverter.toSchema(node).mergeTo(schema)
        }
    }
}
