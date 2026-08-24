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
        properties.path("unionInteger").path("default").isIntegralNumber.assert().isTrue()
        properties.path("array").path("default").isArray.assert().isTrue()
        properties.path("objectValue").path("default").isObject.assert().isTrue()
        properties.path("booleanValue").path("default").booleanValue().assert().isTrue()
        properties.path("nullableInteger").run {
            path("default").isNull.assert().isTrue()
            path("anyOf").path(0).path("type").stringValue().assert().isEqualTo("null")
            path("anyOf").path(1).path("type").stringValue().assert().isEqualTo("integer")
        }
        properties.path("stringValue").path("default").run {
            isString.assert().isTrue()
            stringValue().assert().isEqualTo("true")
        }
    }

    @Test
    fun `should generate typed default for getter-only property`() {
        val generator = SchemaGeneratorBuilder().customizer { }.build()

        val default = generator.generateSchema(GetterDefault::class.java)
            .path("properties")
            .path("number")
            .path("default")

        default.isNumber.assert().isTrue()
        default.doubleValue().assert().isEqualTo(1.5)
    }

    @Test
    fun `should preserve literal null default for nullable string`() {
        val generator = SchemaGeneratorBuilder().customizer { }.build()

        val default = generator.generateSchema(NullableStringDefault::class.java)
            .path("properties")
            .path("value")
            .path("default")

        default.isString.assert().isTrue()
        default.stringValue().assert().isEqualTo("null")
    }

    @Test
    fun `should preserve defaults incompatible with declared type`() {
        val generator = SchemaGeneratorBuilder().customizer { }.build()

        val properties = generator.generateSchema(IncompatibleDefaults::class.java).path("properties")

        properties.path("mismatchedInteger").path("default").stringValue().assert().isEqualTo("true")
        properties.path("malformedInteger").path("default").stringValue().assert().isEqualTo("not-json")
        properties.path("nonNullableInteger").path("default").stringValue().assert().isEqualTo("null")
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
        @get:Schema(types = ["integer", "null"], defaultValue = "0")
        val unionInteger: Int = 0,
        @get:Schema(defaultValue = "[]")
        val array: List<String> = emptyList(),
        @get:Schema(defaultValue = "{}")
        val objectValue: Map<String, String> = emptyMap(),
        @get:Schema(defaultValue = "true")
        val booleanValue: Boolean = true,
        @get:Schema(defaultValue = "null")
        val nullableInteger: Int? = null,
        @get:Schema(defaultValue = "true")
        val stringValue: String = "true",
    )

    private class GetterDefault {
        @get:Schema(defaultValue = "1.5")
        val number: Double
            get() = 1.5
    }

    private data class NullableStringDefault(
        @get:Schema(defaultValue = "null")
        val value: String? = "null",
    )

    private data class IncompatibleDefaults(
        @get:Schema(defaultValue = "true")
        val mismatchedInteger: Int = 0,
        @get:Schema(defaultValue = "not-json")
        val malformedInteger: Int = 0,
        @get:Schema(defaultValue = "null")
        val nonNullableInteger: Int = 0,
    )

    private class FieldDefault {
        @JvmField
        @field:Schema(defaultValue = "0")
        val integer: Int = 0
    }
}
