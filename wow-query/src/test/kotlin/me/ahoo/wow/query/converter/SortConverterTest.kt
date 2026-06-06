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
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test

class SortConverterTest {

    private class RecordingSortConverter(
        override val fieldConverter: FieldConverter = FieldConverter { it }
    ) : AbstractSortConverter<List<Sort>>() {
        var lastConverted: List<Sort> = emptyList()

        override fun internalConvert(sort: List<Sort>): List<Sort> {
            lastConverted = sort
            return sort
        }
    }

    @Test
    fun `should convert empty sort list`() {
        val converter = RecordingSortConverter()
        val result = converter.convert(emptyList())
        result.assert().isEmpty()
    }

    @Test
    fun `should convert sort with field converter`() {
        val converter = RecordingSortConverter(
            fieldConverter = FieldConverter { "prefix.$it" }
        )
        val sort = listOf(Sort("field1", Sort.Direction.ASC))
        converter.convert(sort)
        converter.lastConverted.assert().isEqualTo(
            listOf(Sort("prefix.field1", Sort.Direction.ASC))
        )
    }

    @Test
    fun `should convert multiple sort fields`() {
        val converter = RecordingSortConverter()
        val sorts = listOf(
            Sort("field1", Sort.Direction.ASC),
            Sort("field2", Sort.Direction.DESC)
        )
        val result = converter.convert(sorts)
        result.assert().isEqualTo(sorts)
    }

    @Test
    fun `should pass through without field converter`() {
        val converter = RecordingSortConverter()
        val sorts = listOf(Sort("field1", Sort.Direction.ASC))
        converter.convert(sorts)
        converter.lastConverted.assert().isEqualTo(sorts)
    }
}
