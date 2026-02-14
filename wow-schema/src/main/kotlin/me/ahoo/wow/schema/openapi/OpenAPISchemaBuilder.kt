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
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.TypeContext
import io.swagger.v3.core.util.ObjectMapperFactory
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.naming.DefaultSchemaNamePrefixCapable
import me.ahoo.wow.schema.naming.SchemaNamingModule
import me.ahoo.wow.schema.openapi.SchemaMerger.mergeTo
import me.ahoo.wow.serialization.toLinkedHashMap
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.lang.reflect.Type

class OpenAPISchemaBuilder(
    override val defaultSchemaNamePrefix: String = "",
    private val schemaGeneratorBuilder: SchemaGeneratorBuilder = SchemaGeneratorBuilder(),
    private val definitionPath: String = DEFAULT_DEFINITION_PATH
) : DefaultSchemaNamePrefixCapable, InlineSchemaCapable {
    companion object {
        const val DEFAULT_DEFINITION_PATH = "components/schemas"
    }

    private val schemaGenerator: SchemaGenerator = schemaGeneratorBuilder
        .schemaNamingModule(SchemaNamingModule(defaultSchemaNamePrefix))
        .build()
    private val typeContext: TypeContext = schemaGeneratorBuilder.requiredTypeContent

    override val inline: Boolean
        get() = schemaGenerator.config.shouldInlineAllSchemas()
    private val schemaBuilder = schemaGenerator.buildMultipleSchemaDefinitions()
    private val schemaReferences: MutableList<SchemaReference> = mutableListOf()
    private val openAPIObjectMapper = ObjectMapperFactory.create(null, true)
    fun JsonNode.toSchema(): Schema<*> {
        return openAPIObjectMapper.convertValue(this.toLinkedHashMap(), Schema::class.java)
    }

    fun resolveType(mainTargetType: Type, vararg typeParameters: Type): ResolvedType {
        return typeContext.resolve(mainTargetType, *typeParameters)
    }

    fun generateSchema(mainTargetType: Type, vararg typeParameters: Type): Schema<*> {
        val resolvedType = typeContext.resolve(mainTargetType, *typeParameters)
        if (inline) {
            return schemaGenerator.generateSchema(resolvedType).toSchema()
        }
        val refSchemaNode = schemaBuilder.createSchemaReference(resolvedType)
        val schemaReference = SchemaReference(resolvedType, refSchemaNode.toSchema(), refSchemaNode)
        schemaReferences.add(schemaReference)
        return schemaReference.schema
    }

    fun build(): Map<String, Schema<*>> {
        val collectedDefs = schemaBuilder.collectDefinitions(definitionPath)
        for (schemaReference in schemaReferences) {
            schemaReference.merge()
        }
        return collectedDefs.properties().associate { (name, node) ->
            name to node.toSchema()
        }
    }

    inner class SchemaReference(val type: ResolvedType, val schema: Schema<*>, val node: ObjectNode) {
        fun merge() {
            node.toSchema().mergeTo(schema)
        }
    }
}
