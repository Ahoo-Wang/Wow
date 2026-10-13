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

package me.ahoo.wow.kernel

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * A state holding sets and maps loads back from its own text through the strict [EventCodec.decodeState]: a set reads
 * back in the order it was written, so it writes back as the same JSON, while a text it cannot hold is still refused.
 */
class StateCollectionsRoundTripTest {
    enum class Color { RED, GREEN, BLUE, YELLOW, BLACK, WHITE }

    data class Line(val sku: String, val labels: Set<String>)

    data class Holder(
        val tags: Set<String>,
        val colors: Set<Color>,
        val ordered: LinkedHashSet<String>,
        val counts: Map<String, Int>,
        val byColor: Map<Color, BigDecimal>,
        val line: Line,
    )

    /** A mutable state (D7) whose sets are filled in place. */
    class Basket {
        val items: MutableSet<String> = mutableSetOf()
        var colors: Set<Color> = emptySet()
    }

    private val codec = EventCodec()
    private val model = aggregate<Holder>("test", "holder") {
        initial { Holder(emptySet(), emptySet(), linkedSetOf(), emptyMap(), emptyMap(), Line("", emptySet())) }
    }
    private val basketModel = aggregate<Basket>("test", "basket") { initial { Basket() } }

    /** Not in hash order: a HashSet would give them back in another. */
    private val tags = (1..20).map { "tag-$it" }.reversed()

    private fun holder() = Holder(
        tags = tags.toSet(),
        // Not in ordinal order: an EnumSet would give them back in another.
        colors = linkedSetOf(Color.WHITE, Color.RED, Color.BLUE, Color.GREEN),
        ordered = linkedSetOf("z", "a", "m"),
        counts = linkedMapOf("z" to 1, "a" to 2),
        byColor = linkedMapOf(Color.WHITE to BigDecimal("2.50"), Color.RED to BigDecimal("0.10")),
        line = Line("sku", tags.toSet()),
    )

    @Test
    fun `a state with sets and maps loads back from its own text`() {
        val text = codec.stateText(holder())
        val state = codec.decodeState(model, text)
        state.assert().isEqualTo(holder())
        state.tags.toList().assert().isEqualTo(tags)
        state.colors.toList().assert().containsExactly(Color.WHITE, Color.RED, Color.BLUE, Color.GREEN)
        state.line.labels.toList().assert().isEqualTo(tags)
        state.byColor.getValue(Color.WHITE).assert().isEqualTo(BigDecimal("2.50"))
        codec.stateText(state).assert().isEqualTo(text)
    }

    @Test
    fun `a mutable state's sets load back in place, in the order written`() {
        val basket = Basket().apply {
            items += tags
            colors = linkedSetOf(Color.BLACK, Color.RED)
        }
        val text = codec.stateText(basket)
        val state = codec.decodeState(basketModel, text)
        state.items.toList().assert().isEqualTo(tags)
        state.colors.toList().assert().containsExactly(Color.BLACK, Color.RED)
        codec.stateText(state).assert().isEqualTo(text)
    }

    @Test
    fun `a set given back with a repeated element is refused`() {
        // The set cannot hold both: the state would decide on less than its snapshot holds.
        val text = """{"items":["a","a"],"colors":[]}"""
        assertThrows<IllegalArgumentException> { codec.decodeState(basketModel, text) }
    }
}
