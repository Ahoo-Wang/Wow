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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary
import org.junit.jupiter.api.Test

class DescriptionResolverTest {
    private val jsonSchemaGenerator = SchemaGeneratorBuilder().build()

    @Test
    fun `should resolve summary as field title`() {
        val schema = jsonSchemaGenerator.generateSchema(ChangeTestName::class.java)
        val nameField = schema.get("properties").get("name")
        nameField.get("title").stringValue().assert().isEqualTo("newName")
    }

    @Test
    fun `should resolve description as field description`() {
        val schema = jsonSchemaGenerator.generateSchema(ChangeTestName::class.java)
        val nameField = schema.get("properties").get("name")
        nameField.get("description").stringValue().assert().isEqualTo("The new name to set")
    }

    @Test
    fun `should resolve summary and description from annotation fixture`() {
        val schema = jsonSchemaGenerator.generateSchema(AnnotationFixture::class.java)
        val nullableField = schema.get("properties").get("nullableField")
        nullableField.get("title").stringValue().assert().isEqualTo("titleField")
        nullableField.get("description").stringValue().assert().isEqualTo("descField")
    }

    @Test
    fun `should not set title for field without summary`() {
        val schema = jsonSchemaGenerator.generateSchema(TestAddress::class.java)
        val countryField = schema.get("properties").get("country")
        countryField.get("title").assert().isNull()
    }

    @Summary("typeSummary")
    @Description("typeDescription")
    data class TypeAnnotationFixture(
        val value: String,
    )

    @Test
    fun `should resolve summary as type title`() {
        val schema = jsonSchemaGenerator.generateSchema(TypeAnnotationFixture::class.java)
        schema.get("title").assert().isNotNull()
        schema.get("title").stringValue().assert().isEqualTo("typeSummary")
    }

    @Test
    fun `should resolve description as type description`() {
        val schema = jsonSchemaGenerator.generateSchema(TypeAnnotationFixture::class.java)
        schema.get("description").assert().isNotNull()
        schema.get("description").stringValue().assert().isEqualTo("typeDescription")
    }
}
