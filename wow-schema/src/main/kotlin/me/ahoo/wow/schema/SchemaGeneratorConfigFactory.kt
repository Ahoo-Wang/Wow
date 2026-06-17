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

import com.github.victools.jsonschema.generator.Module
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder

internal object SchemaGeneratorConfigFactory {

    fun create(builder: SchemaGeneratorBuilder): SchemaGeneratorConfigBuilder {
        return SchemaGeneratorConfigBuilder(builder.schemaVersion, builder.optionPreset)
            .withModuleIfPresent(builder.jacksonModule)
            .withModuleIfPresent(builder.jakartaValidationModule)
            .withModuleIfPresent(builder.swagger2Module)
            .withModuleIfPresent(builder.kotlinModule)
            .withModuleIfPresent(builder.jodaMoneyModule)
            .withModuleIfPresent(builder.wowModule)
            .withModuleIfPresent(builder.schemaNamingModule)
            .withOptions(builder.options)
            .also { configBuilder ->
                configBuilder.forFields()
                builder.customizer?.accept(configBuilder)
            }
    }

    private fun SchemaGeneratorConfigBuilder.withModuleIfPresent(
        module: Module?
    ): SchemaGeneratorConfigBuilder {
        module?.let {
            with(it)
        }
        return this
    }

    private fun SchemaGeneratorConfigBuilder.withOptions(
        options: List<Option>
    ): SchemaGeneratorConfigBuilder {
        options.forEach {
            with(it)
        }
        return this
    }
}
