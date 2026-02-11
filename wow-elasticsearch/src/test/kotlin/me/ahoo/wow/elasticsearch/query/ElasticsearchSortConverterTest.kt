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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.SortOrder
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortConverter.toSortOptions
import me.ahoo.wow.query.dsl.sort
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElasticsearchSortConverterTest {

    @Test
    fun `toSortOptions - should convert Sort to SortOptions`() {
        val sort = sort {
            "field1".asc()
            "field2".desc()
        }

        val actual = sort.toSortOptions()

        actual.first().let {
            it.field().field().assert().isEqualTo("field1")
            it.field().order().assert().isEqualTo(SortOrder.Asc)
        }
        actual.last().let {
            it.field().field().assert().isEqualTo("field2")
            it.field().order().assert().isEqualTo(SortOrder.Desc)
        }
    }

    @Test
    fun `toSortOptions - should convert empty Sort to empty SortOptions`() {
        val sort = emptyList<Sort>()

        val actual = sort.toSortOptions()

        assertTrue(actual.isEmpty())
    }
}
