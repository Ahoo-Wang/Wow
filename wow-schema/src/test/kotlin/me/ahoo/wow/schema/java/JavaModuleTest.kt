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

package me.ahoo.wow.schema.java

import me.ahoo.test.asserts.assert
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class JavaModuleTest {
    private val jsonSchemaGenerator = SchemaGeneratorBuilder().build()

    @Test
    fun `should mark record components as required`() {
        val schema = jsonSchemaGenerator.generateSchema(JavaFixtures.RecordFixture::class.java)
        schema.requiredNames().assert().containsExactlyInAnyOrder("name", "balance")
    }

    @Test
    fun `should respect an explicit required mode on a record component`() {
        val schema = jsonSchemaGenerator.generateSchema(JavaFixtures.AnnotatedRecordFixture::class.java)
        schema.requiredNames().assert().containsExactly("name")
    }

    @Test
    fun `should leave plain java beans without required fields`() {
        val schema = jsonSchemaGenerator.generateSchema(JavaFixtures.BeanFixture::class.java)
        schema.requiredNames().assert().isEmpty()
    }

    @Test
    fun `should skip the required check when the java module is removed`() {
        val generator = SchemaGeneratorBuilder().javaModule(null).build()
        generator.generateSchema(JavaFixtures.RecordFixture::class.java).requiredNames().assert().isEmpty()
    }

    private fun JsonNode.requiredNames(): List<String> {
        return path("required").toList().map { it.stringValue() }
    }
}
