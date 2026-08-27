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

package me.ahoo.wow.schema.naming

import me.ahoo.test.asserts.assert
import me.ahoo.wow.schema.CreateTestAggregate
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import org.junit.jupiter.api.Test

class SchemaNamingModuleTest {

    @Test
    fun `should have default schema name prefix`() {
        val module = SchemaNamingModule("TestPrefix.")
        module.defaultSchemaNamePrefix.assert().isEqualTo("TestPrefix.")
    }

    @Test
    fun `should have empty default schema name prefix by default`() {
        val module = SchemaNamingModule()
        module.defaultSchemaNamePrefix.assert().isEmpty()
    }

    @Test
    fun `should generate schema with definition naming strategy`() {
        val generator = SchemaGeneratorBuilder().schemaNamingModule(SchemaNamingModule("TestPrefix.")).build()
        val schema = generator.generateSchema(CreateTestAggregate::class.java)
        schema.assert().isNotNull()
    }

    @Test
    fun `should generate schema without prefix when default is empty`() {
        val generator = SchemaGeneratorBuilder().schemaNamingModule(SchemaNamingModule("")).build()
        val schema = generator.generateSchema(CreateTestAggregate::class.java)
        schema.assert().isNotNull()
    }
}
