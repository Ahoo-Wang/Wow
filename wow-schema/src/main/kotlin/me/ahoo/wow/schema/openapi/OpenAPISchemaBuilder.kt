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
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.Module
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.generator.TypeContext
import com.github.victools.jsonschema.generator.impl.TypeContextFactory
import com.github.victools.jsonschema.module.jackson.JacksonModule
import com.github.victools.jsonschema.module.jackson.JacksonOption
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption
import com.github.victools.jsonschema.module.swagger2.Swagger2Module
import io.swagger.v3.core.util.ObjectMapperFactory
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.schema.WowModule
import me.ahoo.wow.schema.joda.money.JodaMoneyModule
import me.ahoo.wow.schema.kotlin.KotlinModule
import me.ahoo.wow.schema.naming.DefaultSchemaNamePrefixCapable
import me.ahoo.wow.schema.naming.SchemaNamingModule
import me.ahoo.wow.schema.openapi.OpenAPISchemaBuilder.DefaultCustomizer.Companion.defaultConfig
import me.ahoo.wow.schema.openapi.SchemaMerger.mergeTo
import java.lang.reflect.Type
import java.util.function.Consumer

class OpenAPISchemaBuilder(
    private val schemaVersion: SchemaVersion = SchemaVersion.DRAFT_2020_12,
    private val optionPreset: OptionPreset = OptionPreset.PLAIN_JSON,
    override val defaultSchemaNamePrefix: String = "",
    private val customizer: Consumer<SchemaGeneratorConfigBuilder> = DefaultCustomizer(defaultSchemaNamePrefix),
    private val openapi31: Boolean = true,
    private val definitionPath: String = DEFAULT_DEFINITION_PATH
) : DefaultSchemaNamePrefixCapable, InlineSchemaCapable {
    companion object {
        const val DEFAULT_DEFINITION_PATH = "components/schemas"
    }

    private val openAPIObjectMapper = ObjectMapperFactory.create(null, openapi31)
    private val generatorConfig: SchemaGeneratorConfig =
        SchemaGeneratorConfigBuilder(openAPIObjectMapper, schemaVersion, optionPreset)
            .also {
                customizer.accept(it)
            }.build()
    private val typeContext: TypeContext = TypeContextFactory.createDefaultTypeContext(generatorConfig)
    private val schemaGenerator: SchemaGenerator = SchemaGenerator(generatorConfig, typeContext)

    override val inline: Boolean
        get() = generatorConfig.shouldInlineAllSchemas()
    private val schemaBuilder = schemaGenerator.buildMultipleSchemaDefinitions()
    private val schemaReferences: MutableList<SchemaReference> = mutableListOf()

    fun JsonNode.toSchema(): Schema<*> {
        return openAPIObjectMapper.treeToValue(this, Schema::class.java)
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

    class DefaultCustomizer(override val defaultSchemaNamePrefix: String = "") :
        DefaultSchemaNamePrefixCapable, Consumer<SchemaGeneratorConfigBuilder> {

        companion object {
            fun SchemaGeneratorConfigBuilder.defaultConfig(
                defaultSchemaNamePrefix: String
            ): SchemaGeneratorConfigBuilder {
                val jacksonModule: Module = JacksonModule(
                    JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE,
                    JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY,
                    JacksonOption.RESPECT_JSONPROPERTY_ORDER,
                    JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                    JacksonOption.INLINE_TRANSFORMED_SUBTYPES
                )
                val jakartaModule = JakartaValidationModule(
                    JakartaValidationOption.PREFER_IDN_EMAIL_FORMAT,
                    JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS
                )
                val openApiModule: Module = Swagger2Module()
                val kotlinModule = KotlinModule()
                val jodaMoneyModule = JodaMoneyModule()
                val wowModule = WowModule()
                val schemaNamingModule = SchemaNamingModule(defaultSchemaNamePrefix)
                with(jacksonModule)
                    .with(jakartaModule)
                    .with(openApiModule)
                    .with(kotlinModule)
                    .with(jodaMoneyModule)
                    .with(wowModule)
                    .with(schemaNamingModule)
                    .with(Option.EXTRA_OPEN_API_FORMAT_VALUES)
                    .with(Option.PLAIN_DEFINITION_KEYS)
                    .with(Option.SIMPLIFIED_ENUMS)
                    .with(Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
                return this
            }
        }

        override fun accept(configBuilder: SchemaGeneratorConfigBuilder) {
            configBuilder.defaultConfig(defaultSchemaNamePrefix)
                .with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
        }
    }

    class InlineCustomizer(override val defaultSchemaNamePrefix: String = "") :
        DefaultSchemaNamePrefixCapable, Consumer<SchemaGeneratorConfigBuilder> {
        override fun accept(configBuilder: SchemaGeneratorConfigBuilder) {
            configBuilder.defaultConfig(defaultSchemaNamePrefix)
                .with(Option.INLINE_ALL_SCHEMAS)
        }
    }
}
