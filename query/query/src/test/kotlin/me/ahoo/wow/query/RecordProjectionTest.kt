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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.backend.RecordProjection
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

class RecordProjectionTest {
    private fun record(json: String): ObjectNode = WowJson.mapper.readTree(json) as ObjectNode

    private fun include(record: ObjectNode, vararg path: String): String =
        RecordProjection.project(record, listOf(path.toList()), emptyList()).toString()

    private fun exclude(record: ObjectNode, vararg path: String): String =
        RecordProjection.project(record.deepCopy(), emptyList(), listOf(path.toList())).toString()

    @Test
    fun `a projection keeps each object element's subtree at its position`() {
        val record = record("""{"lines":[{"sku":"A","n":1},2,{"n":3}],"name":"x"}""")
        include(record, "lines", "sku").assert().isEqualTo("""{"lines":[{"sku":"A"},{},{}]}""")
        include(record, "missing", "sku").assert().isEqualTo("{}")
        include(record, "name", "sku").assert().isEqualTo("{}")
        exclude(record, "lines", "n").assert().isEqualTo("""{"lines":[{"sku":"A"},2,{}],"name":"x"}""")
        exclude(record, "missing", "n").assert().isEqualTo(record.toString())
    }

    @Test
    fun `a scalar element is an empty object before a kept element and left out after the last`() {
        include(record("""{"xs":[1,{"a":2},3]}"""), "xs", "a").assert().isEqualTo("""{"xs":[{},{"a":2}]}""")
        include(record("""{"xs":[1,2]}"""), "xs", "a").assert().isEqualTo("""{"xs":[]}""")
        exclude(record("""{"xs":[1,{"a":2,"b":3}]}"""), "xs", "a").assert().isEqualTo("""{"xs":[1,{"b":3}]}""")
    }

    @Test
    fun `a projection keeps the nesting of an array of arrays`() {
        val record = record("""{"grid":[[{"city":"x","zip":"1"},{"city":"y"}],[],[{"zip":"2"}]],"name":"n"}""")
        include(record, "grid", "city").assert().isEqualTo("""{"grid":[[{"city":"x"},{"city":"y"}],[],[{}]]}""")
        exclude(record, "grid", "zip")
            .assert().isEqualTo("""{"grid":[[{"city":"x"},{"city":"y"}],[],[{}]],"name":"n"}""")
    }

    @Test
    fun `two includes under one array fill the same elements`() {
        val record = record("""{"grid":[[{"city":"x","zip":"1","n":0}]]}""")
        RecordProjection.project(record, listOf(listOf("grid", "city"), listOf("grid", "zip")), emptyList())
            .toString().assert().isEqualTo("""{"grid":[[{"city":"x","zip":"1"}]]}""")
    }

    @Test
    fun `no include and no exclude is the record itself`() {
        val record = record("""{"name":"x"}""")
        (RecordProjection.project(record, emptyList(), emptyList()) === record).assert().isTrue()
    }
}
