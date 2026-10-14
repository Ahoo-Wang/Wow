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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode

class StateDiffTest {
    private fun json(text: String): JsonNode = WowJson.mapper.readTree(text)

    private fun diff(nineX: String, v10: String, mapping: StateMapping = StateMapping()) =
        StateDiff.diff(json(nineX), json(v10), mapping)

    @Test
    fun `equal states have no difference, numbers by value, fields in any order, null as missing`() {
        diff(
            """{"amount":25.00,"count":2,"items":[{"a":1,"b":null}],"note":null}""",
            """{"items":[{"a":1.0}],"count":2.0,"amount":25}""",
        ).assert().isEmpty()
    }

    @Test
    fun `each differing field is named by its path, with both values`() {
        val differences = diff(
            """{"amount":25,"status":"PAID","items":[{"price":1},{"price":2}],"gone":true,"code":"1"}""",
            """{"amount":20,"status":"PAID","items":[{"price":1},{"price":3},{"price":4}],"added":1,"code":1}""",
        )
        differences.map { it.path }.assert()
            .containsExactly("added", "amount", "code", "gone", "items[1].price", "items[2]")
        differences.first { it.path == "items[1].price" }.kind.assert().isEqualTo("items[].price")
        differences.first { it.path == "amount" }.render().assert().isEqualTo("amount: 9.x 25, V10 20")
        differences.first { it.path == "added" }.render().assert().isEqualTo("added: 9.x (none), V10 1")
        differences.first { it.path == "gone" }.render().assert().isEqualTo("gone: 9.x true, V10 (none)")
    }

    @Test
    fun `a whole state that differs in kind is one difference`() {
        diff("[1]", """{"a":1}""").single().path.assert().isEqualTo("(state)")
    }

    @Test
    fun `the state mapping renames 9x fields and leaves ignored ones out on both sides`() {
        val mapping = StateMapping(
            fields = mapOf("orderStatus" to "status"),
            ignore = listOf("payable", "items.totalPrice", "address.zip"),
        )
        diff(
            """{"orderStatus":"PAID","payable":0,"items":[{"id":1,"totalPrice":2}],"address":{"zip":"1"}}""",
            """{"status":"PAID","items":[{"id":1}],"address":{}}""",
            mapping,
        ).assert().isEmpty()
        diff("""{"orderStatus":"PAID"}""", """{"orderStatus":"PAID"}""", mapping).map { it.path }.assert()
            .containsExactly("orderStatus", "status")
    }

    @Test
    fun `a state mapping names its fields`() {
        assertThrows<IllegalArgumentException> { StateMapping(fields = mapOf(" " to "a")) }
        assertThrows<IllegalArgumentException> { StateMapping(fields = mapOf("a" to "")) }
        assertThrows<IllegalArgumentException> { StateMapping(ignore = listOf("a..b")) }
    }
}
