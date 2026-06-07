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

package me.ahoo.wow.schema.kotlin

import me.ahoo.test.asserts.assert
import me.ahoo.wow.schema.KotlinFixture
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import org.junit.jupiter.api.Test

class KotlinModuleTest {
    private val jsonSchemaGenerator = SchemaGeneratorBuilder().build()

    @Test
    fun `should mark nullable field as anyOf null and type`() {
        val schema = jsonSchemaGenerator.generateSchema(KotlinFixture::class.java)
        val nullableField = schema.get("properties").get("nullableField")
        val anyOf = nullableField.get("anyOf")
        anyOf.isArray.assert().isTrue()
        anyOf.get(0).get("type").stringValue().assert().isEqualTo("null")
        anyOf.get(1).get("type").stringValue().assert().isEqualTo("string")
    }

    @Test
    fun `should mark non-null field as not nullable`() {
        val schema = jsonSchemaGenerator.generateSchema(KotlinFixture::class.java)
        val field = schema.get("properties").get("field")
        field.get("anyOf").assert().isNull()
        field.get("type").stringValue().assert().isEqualTo("string")
    }

    @Test
    fun `should mark val property as readOnly`() {
        val schema = jsonSchemaGenerator.generateSchema(KotlinFixture::class.java)
        val readOnlyField = schema.get("properties").get("readOnlyField")
        readOnlyField.get("readOnly").booleanValue().assert().isTrue()
    }

    @Test
    fun `should mark getter as readOnly`() {
        val schema = jsonSchemaGenerator.generateSchema(KotlinFixture::class.java)
        val readOnlyGetter = schema.get("properties").get("readOnlyGetter")
        readOnlyGetter.get("readOnly").booleanValue().assert().isTrue()
    }

    @Test
    fun `should not include private writeOnly field in schema`() {
        val schema = jsonSchemaGenerator.generateSchema(KotlinFixture::class.java)
        val properties = schema.get("properties")
        properties.get("writeOnlyField").assert().isNull()
    }

    @Test
    fun `should list required fields excluding default and readonly`() {
        val schema = jsonSchemaGenerator.generateSchema(KotlinFixture::class.java)
        val required = schema.get("required")
        required.isArray.assert().isTrue()
        val requiredNames = mutableListOf<String>()
        for (i in 0 until required.size()) {
            requiredNames.add(required.get(i).stringValue())
        }
        requiredNames.assert().contains("field")
        requiredNames.assert().contains("nullableField")
        requiredNames.assert().doesNotContain("defaultField")
        requiredNames.assert().doesNotContain("readOnlyField")
        requiredNames.assert().doesNotContain("readOnlyGetter")
    }

    @Test
    fun `should generate char range schema`() {
        val schema = jsonSchemaGenerator.generateSchema(CharRange::class.java)
        schema.assert().isNotNull()
    }

    @Test
    fun `should generate int range schema`() {
        val schema = jsonSchemaGenerator.generateSchema(IntRange::class.java)
        schema.assert().isNotNull()
    }

    @Test
    fun `should generate long range schema`() {
        val schema = jsonSchemaGenerator.generateSchema(LongRange::class.java)
        schema.assert().isNotNull()
    }

    @Test
    fun `should include getter without backing field as property`() {
        val schema = jsonSchemaGenerator.generateSchema(KotlinFixture::class.java)
        val readOnlyGetter = schema.get("properties").get("readOnlyGetter")
        readOnlyGetter.assert().isNotNull()
    }
}
