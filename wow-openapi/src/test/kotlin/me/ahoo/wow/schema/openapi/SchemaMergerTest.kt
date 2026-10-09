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

package me.ahoo.wow.schema.openapi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.schema.openapi.SchemaMerger.mergeTo
import org.junit.jupiter.api.Test

class SchemaMergerTest {

    @Test
    fun `should merge source schema into target`() {
        val source = io.swagger.v3.oas.models.media.Schema<String>()
        source.name("SourceName")
        source.type("string")
        source.description("source description")

        val target = io.swagger.v3.oas.models.media.Schema<String>()
        source.mergeTo(target)

        target.name.assert().isEqualTo("SourceName")
        target.type.assert().isEqualTo("string")
        target.description.assert().isEqualTo("source description")
    }

    @Test
    fun `should merge all common properties`() {
        val source = io.swagger.v3.oas.models.media.Schema<String>()
        source.type("object")
        source.format("date-time")
        source.nullable(true)
        source.deprecated(true)
        source.readOnly(true)
        source.writeOnly(true)

        val target = io.swagger.v3.oas.models.media.Schema<String>()
        source.mergeTo(target)

        target.type.assert().isEqualTo("object")
        target.format.assert().isEqualTo("date-time")
        target.nullable.assert().isTrue()
        target.deprecated.assert().isTrue()
        target.readOnly.assert().isTrue()
        target.writeOnly.assert().isTrue()
    }

    @Test
    fun `should overwrite target with source null values`() {
        val source = io.swagger.v3.oas.models.media.Schema<String>()
        source.type("string")

        val target = io.swagger.v3.oas.models.media.Schema<String>()
        target.name("ExistingName")
        source.mergeTo(target)

        target.name.assert().isNull()
        target.type.assert().isEqualTo("string")
    }

    @Test
    fun `should keep enum and examples unchanged to preserve current merge behavior`() {
        val source = io.swagger.v3.oas.models.media.Schema<String>()
        source._enum(listOf("A", "B"))
        source.examples(listOf("sample"))

        val target = io.swagger.v3.oas.models.media.Schema<String>()
        source.mergeTo(target)

        target.enum.assert().isNull()
        target.examples.assert().isNull()
    }
}
