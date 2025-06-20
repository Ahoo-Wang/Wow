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
import me.ahoo.wow.schema.naming.SchemaNamingModule
import java.util.function.Consumer

class SchemaGeneratorBuilder {
    companion object {
        private fun SchemaGeneratorConfigBuilder.withModule(
            module: com.github.victools.jsonschema.generator.Module?
        ): SchemaGeneratorConfigBuilder {
            module?.let {
                this.with(it)
            }
            return this
        }
    }

    private var openapi31: Boolean = true
    private var schemaVersion: SchemaVersion = SchemaVersion.DRAFT_7
    private var optionPreset: OptionPreset = OptionPreset.PLAIN_JSON

    private var jacksonModule: JacksonModule? = JacksonModule(
        JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE,
        JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY,
        JacksonOption.RESPECT_JSONPROPERTY_ORDER,
        JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
        JacksonOption.INLINE_TRANSFORMED_SUBTYPES
    )
    private var jakartaValidationModule: JakartaValidationModule? = JakartaValidationModule(
        JakartaValidationOption.PREFER_IDN_EMAIL_FORMAT,
        JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS
    )

    private var swagger2Module: Swagger2Module? = Swagger2Module()

    private var kotlinModule: KotlinModule? = KotlinModule()

    private var jodaMoneyModule: JodaMoneyModule? = JodaMoneyModule()
    private var wowModule: WowModule? = WowModule()
    private var schemaNamingModule: SchemaNamingModule? = SchemaNamingModule("")

    private var customizer: Consumer<SchemaGeneratorConfigBuilder>? = Consumer {
        it.with(Option.EXTRA_OPEN_API_FORMAT_VALUES)
            .with(Option.PLAIN_DEFINITION_KEYS)
            .with(Option.SIMPLIFIED_ENUMS)
            .with(Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
            .with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
    }

    fun openapi31(openapi31: Boolean): SchemaGeneratorBuilder {
        this.openapi31 = openapi31
        return this
    }

    fun schemaVersion(schemaVersion: SchemaVersion): SchemaGeneratorBuilder {
        this.schemaVersion = schemaVersion
        return this
    }

    fun optionPreset(optionPreset: OptionPreset): SchemaGeneratorBuilder {
        this.optionPreset = optionPreset
        return this
    }

    fun jacksonModule(jacksonModule: JacksonModule?): SchemaGeneratorBuilder {
        this.jacksonModule = jacksonModule
        return this
    }

    fun jakartaValidationModule(jakartaValidationModule: JakartaValidationModule?): SchemaGeneratorBuilder {
        this.jakartaValidationModule = jakartaValidationModule
        return this
    }

    fun swagger2Module(swagger2Module: Swagger2Module?): SchemaGeneratorBuilder {
        this.swagger2Module = swagger2Module
        return this
    }

    fun kotlinModule(kotlinModule: KotlinModule?): SchemaGeneratorBuilder {
        this.kotlinModule = kotlinModule
        return this
    }

    fun jodaMoneyModule(jodaMoneyModule: JodaMoneyModule?): SchemaGeneratorBuilder {
        this.jodaMoneyModule = jodaMoneyModule
        return this
    }

    fun wowModule(wowModule: WowModule?): SchemaGeneratorBuilder {
        this.wowModule = wowModule
        return this
    }

    fun schemaNamingModule(schemaNamingModule: SchemaNamingModule?): SchemaGeneratorBuilder {
        this.schemaNamingModule = schemaNamingModule
        return this
    }

    fun customizer(customizer: Consumer<SchemaGeneratorConfigBuilder>): SchemaGeneratorBuilder {
        this.customizer = customizer
        return this
    }

    fun build(): SchemaGenerator {
        val openAPIObjectMapper = ObjectMapperFactory.create(null, openapi31)
        val configBuilder = SchemaGeneratorConfigBuilder(openAPIObjectMapper, schemaVersion, optionPreset)
            .withModule(jacksonModule)
            .withModule(jakartaValidationModule)
            .withModule(swagger2Module)
            .withModule(kotlinModule)
            .withModule(jodaMoneyModule)
            .withModule(wowModule)
            .withModule(schemaNamingModule)
        customizer?.accept(configBuilder)
        return SchemaGenerator(configBuilder.build())
    }
}
