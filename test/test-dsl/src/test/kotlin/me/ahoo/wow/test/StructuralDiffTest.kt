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

package me.ahoo.wow.test

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class StructuralDiffTest {
    data class Line(val sku: String, val price: Int)
    data class Order(val id: String, val lines: List<Line>, val note: Map<String, Any?> = emptyMap())
    data class Other(val id: String)

    @Test
    fun `equal values should have no difference`() {
        val order = Order("o", listOf(Line("a", 1)))
        StructuralDiff.of(order, Order("o", listOf(Line("a", 1)))).assert().isEmpty()
        StructuralDiff.of(null, null).assert().isEmpty()
    }

    @Test
    fun `a nested difference should be named by its path`() {
        StructuralDiff.of(Order("o", listOf(Line("a", 1))), Order("o", listOf(Line("a", 2))))
            .assert().containsExactly("lines[0].price: expected 1, was 2")
    }

    @Test
    fun `arrays of other sizes should name the sizes and the common items`() {
        StructuralDiff.of(Order("o", listOf(Line("a", 1), Line("b", 1))), Order("o", listOf(Line("c", 1))))
            .assert().containsExactly("lines: expected 2 items, was 1", "lines[0].sku: expected \"a\", was \"c\"")
    }

    @Test
    fun `a property one side lacks should be nothing`() {
        StructuralDiff.of(Order("o", emptyList(), mapOf("k" to 1)), Order("o", emptyList()))
            .assert().containsExactly("note.k: expected 1, was nothing")
    }

    @Test
    fun `a value of another class should be a difference of its own`() {
        StructuralDiff.of(Order("o", emptyList()), Other("o"))
            .assert().containsExactly("the value: expected a Order, was a Other")
    }

    @Test
    fun `a top-level difference should name the value`() {
        StructuralDiff.of("a", "b").assert().containsExactly("the value: expected \"a\", was \"b\"")
        StructuralDiff.of(null, "b").assert().containsExactly("the value: expected null, was \"b\"")
    }

    @Test
    fun `lists of other sizes should name the unexpected and missing items`() {
        StructuralDiff.of(listOf(Other("a")), listOf(Other("a"), Other("b")))
            .assert().containsExactly("the value: expected 1 items, was 2", "[1]: unexpected Other {\"id\":\"b\"}")
        StructuralDiff.of(listOf(Other("a"), null), listOf(Other("a")))
            .assert().containsExactly("the value: expected 2 items, was 1", "[1]: missing null")
    }

    @Test
    fun `a list against another shape should differ by its class or its tree`() {
        StructuralDiff.of(listOf(1), "x").assert().containsExactly("the value: expected a SingletonList, was a String")
        StructuralDiff.of("x", listOf(1)).assert().containsExactly("the value: expected a String, was a SingletonList")
        StructuralDiff.of(Other("a"), null).assert().containsExactly("the value: expected {\"id\":\"a\"}, was null")
        StructuralDiff.of(mapOf("a" to mapOf("b" to 1)), mapOf("a" to 1))
            .assert().containsExactly("a: expected {\"b\":1}, was 1")
        StructuralDiff.of(mapOf("a" to listOf(1)), mapOf("a" to 1)).assert().containsExactly("a: expected [1], was 1")
        StructuralDiff.of(mapOf("a" to 1), mapOf("a" to listOf(1))).assert().containsExactly("a: expected 1, was [1]")
        StructuralDiff.of(mapOf("a" to 1), mapOf("a" to mapOf("b" to 1)))
            .assert().containsExactly("a: expected 1, was {\"b\":1}")
    }

    @Test
    fun `a description should hold one difference per line`() {
        StructuralDiff.describe(Other("a"), Other("b")).assert().isEqualTo("\n  id: expected \"a\", was \"b\"")
    }
}
