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

import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.module.jackson.JacksonModule
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule
import com.github.victools.jsonschema.module.swagger2.Swagger2Module
import me.ahoo.test.asserts.assert
import me.ahoo.wow.schema.kotlin.KotlinModule
import me.ahoo.wow.schema.naming.SchemaNamingModule
import org.junit.jupiter.api.Test

class SchemaGeneratorBuilderTest {

    @Test
    fun build() {
        SchemaGeneratorBuilder()
            .openapi31(true)
            .schemaVersion(SchemaVersion.DRAFT_2020_12)
            .optionPreset(OptionPreset.PLAIN_JSON)
            .jacksonModule(JacksonModule())
            .jakartaValidationModule(JakartaValidationModule())
            .swagger2Module(Swagger2Module())
            .kotlinModule(KotlinModule())
            .jodaMoneyModule(null)
            .wowModule(WowModule())
            .schemaNamingModule(SchemaNamingModule(""))
            .customizer { }
            .build().assert().isNotNull()
    }
}
