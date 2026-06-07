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

package me.ahoo.wow.schema.jackson

import com.fasterxml.jackson.annotation.JsonBackReference
import com.fasterxml.jackson.annotation.JsonUnwrapped
import me.ahoo.test.asserts.assert
import me.ahoo.wow.schema.IsPrefixFixture
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import org.junit.jupiter.api.Test

class WowJacksonModuleTest {
    private val jsonSchemaGenerator = SchemaGeneratorBuilder().build()

    data class BackRefFixture(
        val name: String,
        @JsonBackReference
        val backRef: String,
    )

    @Test
    fun `should ignore JsonBackReference field`() {
        val schema = jsonSchemaGenerator.generateSchema(BackRefFixture::class.java)
        schema.get("properties").get("backRef").assert().isNull()
        schema.get("properties").get("name").assert().isNotNull()
    }

    data class UnwrappedFixture(
        val name: String,
        @JsonUnwrapped
        val unwrapped: NestedFixture,
    )

    data class NestedFixture(val nestedValue: String)

    @Test
    fun `should ignore JsonUnwrapped field`() {
        val schema = jsonSchemaGenerator.generateSchema(UnwrappedFixture::class.java)
        schema.get("properties").get("unwrapped").assert().isNull()
    }

    @Test
    fun `should not ignore is-prefixed boolean fields for kotlin types`() {
        val schema = jsonSchemaGenerator.generateSchema(IsPrefixFixture::class.java)
        schema.get("properties").get("isOwner").assert().isNotNull()
        schema.get("properties").get("isMissing").assert().isNotNull()
    }
}
