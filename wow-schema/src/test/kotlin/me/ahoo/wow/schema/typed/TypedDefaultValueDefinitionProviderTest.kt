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

package me.ahoo.wow.schema.typed

import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.test.asserts.assert
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import org.junit.jupiter.api.Test

class TypedDefaultValueDefinitionProviderTest {

    @Test
    fun `should generate defaults using declared json types`() {
        val generator = SchemaGeneratorBuilder().customizer { }.build()

        val properties = generator.generateSchema(TypedDefaults::class.java).path("properties")

        properties.path("integer").path("default").run {
            isIntegralNumber.assert().isTrue()
            intValue().assert().isZero()
        }
        properties.path("array").path("default").isArray.assert().isTrue()
        properties.path("objectValue").path("default").isObject.assert().isTrue()
        properties.path("booleanValue").path("default").booleanValue().assert().isTrue()
        properties.path("stringValue").path("default").run {
            isString.assert().isTrue()
            stringValue().assert().isEqualTo("true")
        }
    }

    @Test
    fun `should generate typed default for field`() {
        val generator = SchemaGeneratorBuilder().customizer { }.build()

        val default = generator.generateSchema(FieldDefault::class.java)
            .path("properties")
            .path("integer")
            .path("default")

        default.isIntegralNumber.assert().isTrue()
        default.intValue().assert().isZero()
    }

    private data class TypedDefaults(
        @get:Schema(defaultValue = "0")
        val integer: Int = 0,
        @get:Schema(defaultValue = "[]")
        val array: List<String> = emptyList(),
        @get:Schema(defaultValue = "{}")
        val objectValue: Map<String, String> = emptyMap(),
        @get:Schema(defaultValue = "true")
        val booleanValue: Boolean = true,
        @get:Schema(defaultValue = "true")
        val stringValue: String = "true",
    )

    private class FieldDefault {
        @JvmField
        @field:Schema(defaultValue = "0")
        val integer: Int = 0
    }
}
