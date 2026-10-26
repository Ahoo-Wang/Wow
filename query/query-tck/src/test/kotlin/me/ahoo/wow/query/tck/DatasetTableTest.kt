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

package me.ahoo.wow.query.tck

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class DatasetTableTest {
    @Test
    fun `a column and a table should be values of their properties`() {
        val column = DatasetColumn.of("city", DatasetColumn.Type.STRING)
        column.assert().isEqualTo(DatasetColumn.of("city", DatasetColumn.Type.STRING, nullable = false))
        column.hashCode().assert().isEqualTo(DatasetColumn.of("city", DatasetColumn.Type.STRING).hashCode())
        column.assert().isNotEqualTo(DatasetColumn.of("city", DatasetColumn.Type.STRING, nullable = true))
        column.toString().assert().isEqualTo("DatasetColumn(name=city, type=STRING, nullable=false)")

        val table = DatasetTable.of("orders", listOf(column), listOf(mapOf("city" to "Paris")))
        table.assert().isEqualTo(DatasetTable.of("orders", listOf(column), listOf(mapOf("city" to "Paris"))))
        table.hashCode().assert().isEqualTo(DatasetTable.of("orders", listOf(column), table.rows).hashCode())
        table.assert().isNotEqualTo(DatasetTable.of("other", listOf(column), table.rows))
        table.equals(Any()).assert().isFalse()
        table.toString().assert().startsWith("DatasetTable(name=orders, columns=[")
    }
}
