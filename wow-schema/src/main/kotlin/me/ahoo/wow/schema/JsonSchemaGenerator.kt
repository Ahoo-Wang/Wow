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

package me.ahoo.wow.schema

import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.Module
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.module.jackson.JacksonModule
import com.github.victools.jsonschema.module.jackson.JacksonOption
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption
import com.github.victools.jsonschema.module.swagger2.Swagger2Module
import io.swagger.v3.core.util.ObjectMapperFactory
import me.ahoo.wow.schema.joda.money.JodaMoneyModule
import me.ahoo.wow.schema.kotlin.KotlinModule
import me.ahoo.wow.schema.naming.DefaultSchemaNamePrefixCapable
import me.ahoo.wow.schema.naming.SchemaNamingModule
import java.lang.reflect.Type

class JsonSchemaGenerator(
    private val options: Set<WowOption> = WowOption.ALL,
    override val defaultSchemaNamePrefix: String = ""
) : DefaultSchemaNamePrefixCapable {
    private val schemaGenerator: SchemaGenerator

    init {
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
        val wowModule = WowModule(options)
        val schemaNamingModule = SchemaNamingModule(defaultSchemaNamePrefix)
        val schemaGeneratorConfigBuilder = SchemaGeneratorConfigBuilder(
            ObjectMapperFactory.create(null, true),
            SchemaVersion.DRAFT_2020_12,
            OptionPreset.PLAIN_JSON
        ).with(jacksonModule)
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
        schemaGenerator = SchemaGenerator(schemaGeneratorConfigBuilder.build())
    }

    fun generate(mainTargetType: Type, vararg typeParameters: Type): ObjectNode {
        return schemaGenerator.generateSchema(mainTargetType, *typeParameters)
    }
}
