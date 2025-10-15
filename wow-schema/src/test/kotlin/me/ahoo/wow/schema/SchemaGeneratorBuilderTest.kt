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
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule
import com.github.victools.jsonschema.module.swagger2.Swagger2Module
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.schema.jackson.WowJacksonModule
import me.ahoo.wow.schema.kotlin.KotlinModule
import me.ahoo.wow.schema.naming.SchemaNamingModule
import org.junit.jupiter.api.Test
import java.util.function.Consumer

class SchemaGeneratorBuilderTest {

    @Test
    fun build() {
        val jacksonModule = WowJacksonModule()
        val jakartaValidationModule = JakartaValidationModule()
        val swagger2Module = Swagger2Module()
        val kotlinModule = KotlinModule()
        val jodaMoneyModule = null
        val wowModule = WowModule()
        val schemaNamingModule = SchemaNamingModule("")
        val options = listOf<Option>()
        val customizer = Consumer<SchemaGeneratorConfigBuilder> {
        }
        val schemaGeneratorBuilder = SchemaGeneratorBuilder()
            .openapi31(true)
            .schemaVersion(SchemaVersion.DRAFT_2020_12)
            .optionPreset(OptionPreset.PLAIN_JSON)
            .jacksonModule(jacksonModule)
            .jakartaValidationModule(jakartaValidationModule)
            .swagger2Module(swagger2Module)
            .kotlinModule(kotlinModule)
            .jodaMoneyModule(jodaMoneyModule)
            .wowModule(wowModule)
            .schemaNamingModule(schemaNamingModule)
            .options(options)
            .customizer(customizer)
        schemaGeneratorBuilder.openapi31.assert().isTrue()
        schemaGeneratorBuilder.schemaVersion.assert().isEqualTo(SchemaVersion.DRAFT_2020_12)
        schemaGeneratorBuilder.optionPreset.assert().isEqualTo(OptionPreset.PLAIN_JSON)
        schemaGeneratorBuilder.jacksonModule.assert().isSameAs(jacksonModule)
        schemaGeneratorBuilder.jakartaValidationModule.assert().isSameAs(jakartaValidationModule)
        schemaGeneratorBuilder.swagger2Module.assert().isSameAs(swagger2Module)
        schemaGeneratorBuilder.kotlinModule.assert().isSameAs(kotlinModule)
        schemaGeneratorBuilder.jodaMoneyModule.assert().isNull()
        schemaGeneratorBuilder.wowModule.assert().isSameAs(wowModule)
        schemaGeneratorBuilder.schemaNamingModule.assert().isSameAs(schemaNamingModule)
        schemaGeneratorBuilder.options.assert().isSameAs(options)
        schemaGeneratorBuilder.customizer.assert().isSameAs(customizer)
        schemaGeneratorBuilder.typeContext.assert().isNull()
        assertThrownBy<IllegalStateException> {
            schemaGeneratorBuilder.requiredTypeContent
        }
        val schemaGenerator = schemaGeneratorBuilder.build()

        schemaGenerator.assert().isNotNull()
        schemaGeneratorBuilder.typeContext.assert().isNotNull()
        schemaGeneratorBuilder.requiredTypeContent.assert().isNotNull()
    }
}
