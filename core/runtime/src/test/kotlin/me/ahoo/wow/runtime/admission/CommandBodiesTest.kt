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

package me.ahoo.wow.runtime.admission

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * How admission reads a command's body ([CommandBodies]): a property given twice at any depth, and one the command
 * does not have, are refused with their path; a value of another kind keeps its own reason; a computed property, which
 * the schema marks `readOnly`, may be given or left out.
 */
class CommandBodiesTest {
    data class Line(val sku: String, val quantity: Int = 1)

    data class PlaceOrder(val lines: List<Line>, val meta: Map<String, String> = emptyMap(), val note: String = "")

    data class ProductRef(val id: String, val code: String, val brandName: String) {
        val searchCode: String get() = "$code|$brandName"
    }

    data class CreateOrder(val product: ProductRef, val lines: List<ProductRef> = emptyList()) {
        val label: String get() = "order of ${product.id}"
    }

    private val bodies = CommandBodies(WowJson.mapper)

    private fun violations(json: String): List<Violation> =
        (bodies.decode(CommandBody.Bytes(json.toByteArray()), PlaceOrder::class.java) as Undecodable).violations

    private fun duplicate(path: String) =
        Violation.of(path, "duplicate-property", "Given more than once in its object.")

    @Test
    fun `a property given twice should be named by its path, at the top, in a list's element and in a map`() {
        violations("""{"lines":[],"note":"a","note":"b"}""").assert().containsExactly(duplicate("note"))
        violations("""{"lines":[{"sku":"a"},{"sku":"b","quantity":1,"sku":"c"}]}""").assert()
            .containsExactly(duplicate("lines[1].sku"))
        violations("""{"lines":[],"meta":{"k":"1","k":"2"}}""").assert().containsExactly(duplicate("meta.k"))
    }

    @Test
    fun `a property the command does not have, and a value of another kind, should keep their own reasons`() {
        violations("""{"lines":[{"sku":"a","qty":2}]}""").assert()
            .containsExactly(Violation.of("lines[0].qty", "unknown-property", "Not a property of its type."))
        violations("""{"lines":[{"sku":"a","quantity":1.5}]}""").assert()
            .containsExactly(Violation.of("lines[0].quantity", "wrong-kind", "Expected an integer, given a number."))
        violations("""{"lines":[{"sku":"a","quantity":3000000000}]}""").assert()
            .containsExactly(
                Violation.of("lines[0].quantity", "out-of-range", "Out of range: -2147483648 to 2147483647."),
            )
    }

    @Test
    fun `a tree body should read as its text does, a duplicate being already gone from a tree`() {
        val tree = JsonMapper.builder().build().readTree("""{"lines":[{"sku":"a","qty":2}]}""")
        (bodies.decode(CommandBody.Tree(tree), PlaceOrder::class.java) as Undecodable).violations.assert()
            .containsExactly(Violation.of("lines[0].qty", "unknown-property", "Not a property of its type."))
    }

    @Test
    fun `a computed property should be read into nothing, given or not, at any depth`() {
        fun decoded(json: String) =
            (bodies.decode(CommandBody.Bytes(json.toByteArray()), CreateOrder::class.java) as Decoded).value
        val expected = CreateOrder(ProductRef("p", "c", "b"), listOf(ProductRef("q", "x", "y")))
        decoded("""{"product":{"id":"p","code":"c","brandName":"b"},"lines":[{"id":"q","code":"x","brandName":"y"}]}""")
            .assert().isEqualTo(expected)
        val stale = """{"product":{"id":"p","code":"c","brandName":"b","searchCode":"stale"},""" +
            """"lines":[{"id":"q","code":"x","brandName":"y","searchCode":"stale"}],"label":"stale"}"""
        (decoded(stale) as CreateOrder).let { order ->
            order.assert().isEqualTo(expected)
            order.product.searchCode.assert().isEqualTo("c|b")
            order.lines.single().searchCode.assert().isEqualTo("x|y")
            order.label.assert().isEqualTo("order of p")
        }
        decoded(WowJson.mapper.writeValueAsString(expected)).assert().isEqualTo(expected)
    }
}
