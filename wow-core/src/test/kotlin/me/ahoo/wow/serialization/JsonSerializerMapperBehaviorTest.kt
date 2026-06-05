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

package me.ahoo.wow.serialization

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.core.type.TypeReference
import java.math.BigDecimal

internal class JsonSerializerMapperBehaviorTest {

    @Test
    fun `object mapper should ignore unknown properties when reading Kotlin values`() {
        val dto = """{"name":"John","unknown":"ignored"}""".toObject<MapperDto>()

        dto.assert().isEqualTo(MapperDto("John"))
    }

    @Test
    fun `object mapper should materialize floating numbers as BigDecimal for untyped values`() {
        val map = """{"amount":100.55}""".toObject(object : TypeReference<Map<String, Any>>() {})

        map["amount"].assert().isInstanceOf(BigDecimal::class.java)
        (map["amount"] as BigDecimal).assert().isEqualTo(BigDecimal("100.55"))
    }

    @Test
    fun `object mapper should expose private fields through configured field visibility`() {
        val json = FieldOnlyDto().toJsonString()

        json.assert().contains("hidden")
        json.assert().contains("visible")
    }

    @Test
    fun `helpers should create deep copies through mapper conversion`() {
        val source = MutableDto("before", mutableListOf("a"))

        val copied = source.deepCopy()
        copied.values += "b"

        copied.assert().isNotSameAs(source)
        source.values.assert().isEqualTo(mutableListOf("a"))
        copied.values.assert().isEqualTo(mutableListOf("a", "b"))
    }

    private data class MapperDto(val name: String)
    private data class MutableDto(val name: String, val values: MutableList<String>)

    private class FieldOnlyDto {
        @Suppress("unused")
        private val hidden: String = "visible"
    }
}
