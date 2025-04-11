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
import com.github.victools.jsonschema.module.swagger2.Swagger2Module
import me.ahoo.wow.schema.joda.money.JodaMoneyModule
import me.ahoo.wow.schema.kotlin.KotlinModule
import me.ahoo.wow.serialization.JsonSerializer
import java.lang.reflect.Type

class JsonSchemaGenerator(private val options: Set<WowOption> = WowOption.ALL) {
    private val schemaGenerator: SchemaGenerator

    init {
        val jacksonModule: Module = JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED)
        val jakartaModule = JakartaValidationModule()
        val openApiModule: Module = Swagger2Module()
        val kotlinModule = KotlinModule()
        val jodaMoneyModule = JodaMoneyModule()
        val wowModule = WowModule(options)
        val schemaGeneratorConfigBuilder = SchemaGeneratorConfigBuilder(
            JsonSerializer,
            SchemaVersion.DRAFT_2020_12,
            OptionPreset.PLAIN_JSON
        ).with(jacksonModule)
            .with(jakartaModule)
            .with(openApiModule)
            .with(wowModule)
            .with(jodaMoneyModule)
            .with(kotlinModule)
            .with(Option.EXTRA_OPEN_API_FORMAT_VALUES)
        schemaGenerator = SchemaGenerator(schemaGeneratorConfigBuilder.build())
    }

    fun generate(mainTargetType: Type, vararg typeParameters: Type): ObjectNode {
        return schemaGenerator.generateSchema(mainTargetType, *typeParameters)
    }
}
