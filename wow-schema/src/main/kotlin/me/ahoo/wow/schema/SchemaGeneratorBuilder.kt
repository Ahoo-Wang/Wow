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
import com.github.victools.jsonschema.generator.TypeContext
import com.github.victools.jsonschema.generator.impl.TypeContextFactory
import com.github.victools.jsonschema.module.jackson.JacksonModule
import com.github.victools.jsonschema.module.jackson.JacksonOption
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption
import com.github.victools.jsonschema.module.swagger2.Swagger2Module
import io.swagger.v3.core.util.ObjectMapperFactory
import me.ahoo.wow.schema.jackson.WowJacksonModule
import me.ahoo.wow.schema.joda.money.JodaMoneyModule
import me.ahoo.wow.schema.kotlin.KotlinModule
import me.ahoo.wow.schema.naming.SchemaNamingModule
import java.util.function.Consumer

/**
 * Builder class for constructing a SchemaGenerator with customizable modules and options.
 * This builder allows configuration of various JSON schema generation modules including
 * Jackson, Jakarta Validation, Swagger2, Kotlin, Joda Money, and Wow-specific modules.
 */
class SchemaGeneratorBuilder {
    companion object {
        /**
         * Extension function to conditionally add a module to the config builder.
         */
        /**
         * Conditionally adds a module to the schema generator config builder if not null.
         */
        private fun SchemaGeneratorConfigBuilder.withModule(
            module: com.github.victools.jsonschema.generator.Module?
        ): SchemaGeneratorConfigBuilder {
            module?.let {
                this.with(it)
            }
            return this
        }

        /**
         * Adds a list of options to the schema generator config builder.
         */
        private fun SchemaGeneratorConfigBuilder.withOptions(options: List<Option>): SchemaGeneratorConfigBuilder {
            options.forEach {
                this.with(it)
            }
            return this
        }
    }

    /** Whether to use OpenAPI 3.1 specification. */
    var openapi31: Boolean = true
        private set

    /** The JSON Schema version to use for generation. */
    var schemaVersion: SchemaVersion = SchemaVersion.DRAFT_7
        private set

    /** The preset of options to apply to the schema generator. */
    var optionPreset: OptionPreset = OptionPreset.PLAIN_JSON
        private set

    /** Jackson module for handling Jackson annotations in schema generation. */
    var jacksonModule: JacksonModule? =
        WowJacksonModule(
            JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE,
            JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY,
            JacksonOption.RESPECT_JSONPROPERTY_ORDER,
            JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
            JacksonOption.INLINE_TRANSFORMED_SUBTYPES,
        )
        private set
    var jakartaValidationModule: JakartaValidationModule? =
        JakartaValidationModule(
            JakartaValidationOption.PREFER_IDN_EMAIL_FORMAT,
            JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS,
        )
        private set

    var swagger2Module: Swagger2Module? = Swagger2Module()
        private set
    var kotlinModule: KotlinModule? = KotlinModule()
        private set
    var jodaMoneyModule: JodaMoneyModule? = JodaMoneyModule()
        private set
    var wowModule: WowModule? = WowModule()
        private set
    var schemaNamingModule: SchemaNamingModule? = SchemaNamingModule("")
        private set
    var options: List<Option> =
        listOf(
            Option.EXTRA_OPEN_API_FORMAT_VALUES,
            Option.PLAIN_DEFINITION_KEYS,
            Option.SIMPLIFIED_ENUMS,
            Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES,
            Option.INLINE_NULLABLE_SCHEMAS,
            Option.NULLABLE_ALWAYS_AS_ANYOF
        )
        private set
    var customizer: Consumer<SchemaGeneratorConfigBuilder>? =
        Consumer {
            it.with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
        }
        private set
    var typeContext: TypeContext? = null
        private set

    /** Gets the TypeContext after build() has been called. Throws if not built yet. */
    val requiredTypeContent: TypeContext
        get() =
            checkNotNull(typeContext) {
                "typeContext is null, please call SchemaGeneratorBuilder.build() first."
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

    fun options(options: List<Option>): SchemaGeneratorBuilder {
        this.options = options
        return this
    }

    fun customizer(customizer: Consumer<SchemaGeneratorConfigBuilder>): SchemaGeneratorBuilder {
        this.customizer = customizer
        return this
    }

    /**
     * Builds and returns a SchemaGenerator instance with the configured modules and options.
     * This method must be called before accessing requiredTypeContent.
     */
    fun build(): SchemaGenerator {
        val openAPIObjectMapper = ObjectMapperFactory.create(null, openapi31)
        val configBuilder =
            SchemaGeneratorConfigBuilder(openAPIObjectMapper, schemaVersion, optionPreset)
                .withModule(jacksonModule)
                .withModule(jakartaValidationModule)
                .withModule(swagger2Module)
                .withModule(kotlinModule)
                .withModule(jodaMoneyModule)
                .withModule(wowModule)
                .withModule(schemaNamingModule)
                .withOptions(options)
        configBuilder.forFields()
        customizer?.accept(configBuilder)
        val config = configBuilder.build()
        typeContext = TypeContextFactory.createDefaultTypeContext(config)
        return SchemaGenerator(configBuilder.build(), typeContext)
    }
}
