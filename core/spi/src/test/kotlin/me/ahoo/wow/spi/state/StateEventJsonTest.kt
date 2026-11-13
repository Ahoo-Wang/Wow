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

package me.ahoo.wow.spi.state

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.json.StateJson
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.tck.ExactJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.DatabindException
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A state event's payloads and state made from the codec's text are written as that text: byte for byte the envelope
 * written from their trees, but for the forms a `Double` or a `Float` takes, and read back as the same trees.
 */
class StateEventJsonTest {
    /** The numbers a typed event or state holds, as the codec writes them from their classes. */
    data class Numbers(
        val double: Double = 1.0E20,
        val small: Double = 1.0E-7,
        val plain: Double = 2.5,
        val whole: Double = 3.0,
        val float: Float = 1.5f,
        val negativeZero: Double = -0.0,
        val max: Double = Double.MAX_VALUE,
        val decimal: BigDecimal = BigDecimal("2.50"),
        val precise: BigDecimal = BigDecimal("1234567890.12345678901234567890"),
        val exponent: BigDecimal = BigDecimal("1E+3"),
        val tiny: BigDecimal = BigDecimal("-0.000001"),
        val large: BigInteger = BigInteger("123456789012345678901234567890"),
        val long: Long = Long.MAX_VALUE,
        val beyondDouble: Long = 9_007_199_254_740_993,
    )

    /** Text that must be escaped, and text that need not be. */
    data class Texts(
        val control: String = "\u0000\u0001\u001f\t\n\r\b\u000c",
        val quotes: String = "\"\\/",
        val nonAscii: String = "é ü 中文 😀",
        val separators: String = "  ",
        val markup: String = "<a href='x'>&amp;</a>",
    )

    private val type = AggregateType("shop", "order")

    private fun record(payload: StateEventPayload, state: PublishedState?) = StateEventRecord(
        type.ref("t", "o-1"),
        1,
        "c-1",
        "r-1",
        "shop.order.Place",
        1L,
        listOf(payload),
        state,
    )

    /** The envelope's bytes of [text] as the payload and the state: made from the text, and made from its trees. */
    private fun bytes(text: String): Pair<String, String> {
        val tree = WowJson.mapper.readTree(text)
        val raw = record(StateEventPayload.ofText("shop.order.Placed", 1, text), PublishedState.ofText(1, "f", text))
        val trees = record(StateEventPayload("shop.order.Placed", 1, tree), PublishedState(1, "f", tree))
        return String(EnvelopeCodec.message(raw).value) to String(EnvelopeCodec.message(trees).value)
    }

    private fun deep(levels: Int): Any = (1..levels).fold<Int, Any>(mapOf("leaf" to listOf(1, "a", null))) { inner, _ ->
        mapOf("n" to inner)
    }

    @Test
    fun `the codec's text should be written byte for byte as its tree, every JSON form a backend keeps exactly`() {
        val corpus = listOf(
            WowJson.mapper.readTree(ExactJson.NUMBERS),
            Texts(),
            deep(DEEP),
            emptyMap<String, Any>(),
            mapOf("emptyObject" to emptyMap<String, Any>(), "emptyArray" to emptyList<Any>(), "nothing" to null),
            mapOf("decimals" to listOf(BigDecimal("2.50"), BigDecimal("0.00"), BigDecimal("1E+3"), BigDecimal.TEN)),
        )
        corpus.forEach { value ->
            val text = StateJson.mapper.writeValueAsString(value)
            val (raw, trees) = bytes(text)
            raw.assert().describedAs(text).isEqualTo(trees)
        }
    }

    @Test
    fun `a double's or a float's form should be the codec's, the same value as the tree's`() {
        val text = StateJson.mapper.writeValueAsString(Numbers())
        val (raw, trees) = bytes(text)
        // The codec writes a double as Java writes it; the tree, read as BigDecimal, writes its exponent with a sign
        // and has no negative zero. Every other number is written alike.
        raw.assert().isEqualTo(
            trees.replace("1.0E+20", "1.0E20")
                .replace("1.7976931348623157E+308", "1.7976931348623157E308")
                .replace("\"negativeZero\":0.0", "\"negativeZero\":-0.0"),
        )
        // Read back, both are the same record, every number exactly the same value and scale.
        val fromRaw = EnvelopeCodec.stateEventOf(raw.toByteArray())
        val fromTrees = EnvelopeCodec.stateEventOf(trees.toByteArray())
        fromRaw.assert().isEqualTo(fromTrees)
        ExactJson.of(fromRaw.events.single().payload.toString())
            .assert().isEqualTo(ExactJson.of(fromTrees.events.single().payload.toString()))
        ExactJson.of(fromRaw.state!!.state.toString()).assert().isEqualTo(ExactJson.of(text))
    }

    @Test
    fun `made from text, the tree should be parsed on its first read and once, and made from a tree be that tree`() {
        val payload = StateEventPayload.ofText("shop.order.Placed", 1, ExactJson.NUMBERS)
        val state = PublishedState.ofText(1, "f", ExactJson.NUMBERS)
        payload.payloadText.assert().isEqualTo(ExactJson.NUMBERS)
        state.stateText.assert().isEqualTo(ExactJson.NUMBERS)
        payload.payload.assert().isSameAs(payload.payload)
        state.state.assert().isSameAs(state.state)
        ExactJson.of(payload.payload.toString()).assert().isEqualTo(ExactJson.of(ExactJson.NUMBERS))
        val tree = WowJson.mapper.readTree(ExactJson.NUMBERS)
        StateEventPayload.of("shop.order.Placed", 1, tree).also {
            it.payload.assert().isSameAs(tree)
            it.payloadText.assert().isNull()
        }
        PublishedState.of(1, "f", tree).also {
            it.state.assert().isSameAs(tree)
            it.stateText.assert().isNull()
        }
        // Equal by their trees, whichever they were made from.
        payload.assert().isEqualTo(StateEventPayload.of("shop.order.Placed", 1, tree))
        state.assert().isEqualTo(PublishedState.of(1, "f", tree))
    }

    @Test
    fun `read, an event and a state should be made from trees and refuse what they cannot hold`() {
        val event = WowJson.mapper.readValue(
            """{"name":"shop.order.Placed","revision":2,"payload":{"amount":2.50},"unknown":1}""",
            StateEventPayload::class.java,
        )
        event.payloadText.assert().isNull()
        event.revision.assert().isEqualTo(2)
        WowJson.mapper.writeValueAsString(event)
            .assert().isEqualTo("""{"name":"shop.order.Placed","revision":2,"payload":{"amount":2.50}}""")
        val written = """{"version":3,"schemaFingerprint":"f","state":{}}"""
        val state = WowJson.mapper.readValue(written, PublishedState::class.java)
        state.stateText.assert().isNull()
        WowJson.mapper.writeValueAsString(state).assert().isEqualTo(written)
        assertThrownBy<DatabindException> {
            WowJson.mapper.readValue("""{"name":"shop.order.Placed","revision":1}""", StateEventPayload::class.java)
        }
        assertThrownBy<DatabindException> {
            WowJson.mapper.readValue(
                """{"version":1,"schemaFingerprint":"f","state":null}""",
                PublishedState::class.java,
            )
        }
        assertThrownBy<DatabindException> { WowJson.mapper.readValue("[]", PublishedState::class.java) }
    }

    private companion object {
        const val DEEP = 64
    }
}
