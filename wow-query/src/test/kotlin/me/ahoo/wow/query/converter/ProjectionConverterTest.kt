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

package me.ahoo.wow.query.converter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Projection
import org.junit.jupiter.api.Test

class ProjectionConverterTest {

    private class RecordingProjectionConverter(
        override val fieldConverter: FieldConverter = FieldConverter { it }
    ) : AbstractProjectionConverter<Projection>() {
        var lastConverted: Projection = Projection.ALL

        override fun internalConvert(projection: Projection): Projection {
            lastConverted = projection
            return projection
        }
    }

    @Test
    fun `should convert empty projection`() {
        val converter = RecordingProjectionConverter()
        val result = converter.convert(Projection.ALL)
        result.assert().isEqualTo(Projection.ALL)
    }

    @Test
    fun `should convert projection with field converter`() {
        val converter = RecordingProjectionConverter(
            fieldConverter = FieldConverter { "state.$it" }
        )
        val projection = Projection(
            include = listOf("field1"),
            exclude = listOf("field2")
        )
        converter.convert(projection)
        converter.lastConverted.assert().isEqualTo(
            Projection(
                include = listOf("state.field1"),
                exclude = listOf("state.field2")
            )
        )
    }

    @Test
    fun `should convert projection without field changes`() {
        val converter = RecordingProjectionConverter()
        val projection = Projection(
            include = listOf("field1"),
            exclude = listOf("field2")
        )
        val result = converter.convert(projection)
        result.assert().isEqualTo(projection)
    }

    @Test
    fun `should convert projection with only includes`() {
        val converter = RecordingProjectionConverter()
        val projection = Projection(include = listOf("field1", "field2"), exclude = emptyList())
        converter.convert(projection)
        converter.lastConverted.include.assert().hasSize(2)
        converter.lastConverted.exclude.assert().isEmpty()
    }
}
