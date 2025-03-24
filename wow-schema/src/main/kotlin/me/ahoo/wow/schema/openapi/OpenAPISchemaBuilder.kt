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
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.Module
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaKeyword
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.generator.TypeContext
import com.github.victools.jsonschema.generator.impl.TypeContextFactory
import com.github.victools.jsonschema.module.jackson.JacksonModule
import com.github.victools.jsonschema.module.jackson.JacksonOption
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule
import com.github.victools.jsonschema.module.swagger2.Swagger2Module
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.schema.JsonSchema.Companion.toPropertyName
import me.ahoo.wow.schema.WowModule
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toObject
import java.lang.reflect.Type
import java.util.function.Consumer

class OpenAPISchemaBuilder(
    private val schemaVersion: SchemaVersion = SchemaVersion.DRAFT_2019_09,
    private val optionPreset: OptionPreset = OptionPreset.PLAIN_JSON,
    private val customizer: Consumer<SchemaGeneratorConfigBuilder> = Consumer {
        val jacksonModule: Module = JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED)
        val jakartaModule = JakartaValidationModule()
        val openApiModule: Module = Swagger2Module()
        val wowModule = WowModule()
        it.with(jacksonModule)
            .with(jakartaModule)
            .with(openApiModule)
            .with(wowModule)
            .with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
            .with(Option.PLAIN_DEFINITION_KEYS)

        it.forFields().withInstanceAttributeOverride(OpenAPICompatibilityAttributeOverride(schemaVersion))
        it.forMethods().withInstanceAttributeOverride(OpenAPICompatibilityAttributeOverride(schemaVersion))
    }
) : InlineSchemaCapable {
    companion object {
        const val DEFINITION_PATH = "components/schemas"
    }

    private val generatorConfig: SchemaGeneratorConfig =
        SchemaGeneratorConfigBuilder(JsonSerializer, schemaVersion, optionPreset)
            .also {
                customizer.accept(it)
            }.build()
    private val typeContext: TypeContext = TypeContextFactory.createDefaultTypeContext(generatorConfig)
    private val schemaGenerator: SchemaGenerator = SchemaGenerator(generatorConfig, typeContext)

    override val inline: Boolean
        get() = generatorConfig.shouldInlineAllSchemas()
    private val schemaBuilder = schemaGenerator.buildMultipleSchemaDefinitions()
    private val schemaReferences: MutableList<SchemaReference> = mutableListOf()
    fun resolveType(mainTargetType: Type, vararg typeParameters: Type): ResolvedType {
        return typeContext.resolve(mainTargetType, *typeParameters)
    }

    fun generateSchema(mainTargetType: Type, vararg typeParameters: Type): Schema<*> {
        val resolvedType = typeContext.resolve(mainTargetType, *typeParameters)
        if (inline) {
            return schemaGenerator.generateSchema(resolvedType).toObject()
        }
        val refSchemaNode = schemaBuilder.createSchemaReference(resolvedType)
        val schemaReference = SchemaReference(resolvedType, refSchemaNode.toObject(), refSchemaNode)
        schemaReferences.add(schemaReference)
        return schemaReference.schema
    }

    fun build(): Map<String, Schema<*>> {
        val collectedDefs = schemaBuilder.collectDefinitions(DEFINITION_PATH)
        for (schemaReference in schemaReferences) {
            schemaReference.merge()
        }
        return collectedDefs.properties().associate { (name, node) ->
            name to node.toObject()
        }
    }

    data class SchemaReference(val type: ResolvedType, val schema: Schema<*>, val node: ObjectNode) {

        fun merge() {
            val schemaRef = node.get(SchemaKeyword.TAG_REF.toPropertyName())?.textValue()
            if (schemaRef != null) {
                schema.`$ref` = schemaRef
            }
        }
    }
}
