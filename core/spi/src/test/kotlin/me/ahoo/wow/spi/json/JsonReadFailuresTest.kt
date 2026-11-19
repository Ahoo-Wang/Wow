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

package me.ahoo.wow.spi.json

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.json.JsonReadFailure.Reason
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.annotation.JsonDeserialize
import java.time.Instant

/**
 * Where a refusal is and why ([JsonReadFailures]): the path, the code and the kind given, always; the kind expected
 * only for a scalar class Wow's JSON describes or an enumeration, never for a class whose form is its own.
 */
class JsonReadFailuresTest {
    private val failures = JsonReadFailures(WowJson.mapper)

    data class Item(val sku: String, val quantity: Int)

    data class Order(val items: List<Item>, val tags: Map<String, Int> = emptyMap(), val at: Instant? = null)

    /** Written as its amount, read from a value of its own. */
    class Money(@get:JsonValue val amount: String)

    class Sku
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    constructor(val value: Map<String, String>)

    @JvmInline
    value class Code(val value: String)

    /** Read by a reader of its own, from text alone. */
    @JsonDeserialize(using = Badge.Reader::class)
    class Badge(val text: String) {
        class Reader : ValueDeserializer<Badge>() {
            override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Badge =
                if (p.currentToken() == JsonToken.VALUE_STRING) {
                    Badge(p.string)
                } else {
                    ctxt.handleUnexpectedToken(Badge::class.java, p) as Badge
                }
        }
    }

    data class Shelf(
        val anything: Any,
        val pair: Pair<String, Int>? = null,
        val money: Money? = null,
        val sku: Sku? = null,
        val code: Code? = null,
        val badge: Badge? = null,
        val ints: IntArray? = null,
        val counts: Map<String, Int> = emptyMap(),
        val item: Item? = null,
    )

    private inline fun <reified T> failureOf(json: String): JsonReadFailure {
        val failure = runCatching { WowJson.mapper.readValue(json, T::class.java) }.exceptionOrNull()
        return failures.of(WowJson.mapper.readTree(json), T::class.java, failure as JacksonException)!!
    }

    @Test
    fun `a refusal should name the property by its path in the JSON`() {
        failureOf<Order>("""{"items":[{"sku":"a","quantity":1.5}]}""").run {
            path.assert().isEqualTo("items[0].quantity")
            message.assert().isEqualTo("Expected an integer, given a number.")
        }
        failureOf<Order>("""{"items":[{"sku":"a","quantity":1},{"sku":"b","quantity":3000000000}]}""").run {
            path.assert().isEqualTo("items[1].quantity")
            reason.assert().isEqualTo(Reason.OUT_OF_RANGE)
        }
        failureOf<Order>("""{"items":[],"tags":{"a":"x"}}""").run {
            path.assert().isEqualTo("tags.a")
            message.assert().isEqualTo("Expected an integer, given a string.")
        }
        failureOf<Order>("""{"items":[],"at":[2026]}""").run {
            path.assert().isEqualTo("at")
            message.assert().isEqualTo("Expected a string, given an array.")
        }
    }

    @Test
    fun `a property left out or null should be required, with no type claimed`() {
        failureOf<Order>("""{"items":[{"quantity":1}]}""").run {
            path.assert().isEqualTo("items[0].sku")
            reason.assert().isEqualTo(Reason.WRONG_KIND)
            message.assert().isEqualTo("A value is required, given none.")
        }
        failureOf<Order>("""{"items":[{"sku":null,"quantity":1}]}""").message.assert()
            .isEqualTo("A value is required, given null.")
        failureOf<Shelf>("{}").run {
            path.assert().isEqualTo("anything")
            message.assert().isEqualTo("A value is required, given none.")
        }
    }

    @Test
    fun `a class that is no scalar of Wow's JSON should be given the kind given alone`() {
        mapOf(
            """{"items":{}}""" to ("items" to "an object"),
            "[1]" to ("" to "an array"),
        ).forEach { (json, expected) ->
            failureOf<Order>(json).run {
                path.assert().describedAs(json).isEqualTo(expected.first)
                reason.assert().describedAs(json).isEqualTo(Reason.INVALID_FORMAT)
                message.assert().describedAs(json).isEqualTo("Not a valid value of its type, given ${expected.second}.")
            }
        }
        // A value class, one written by @JsonValue, one read by a reader of its own, a platform class and a container:
        // none is taken for an object.
        mapOf(
            "pair" to "[1]",
            "money" to "[1]",
            "sku" to "[1]",
            "badge" to "1",
            "ints" to "{}",
            "counts" to "[]",
            "item" to "\"x\"",
        ).forEach { (property, value) ->
            failureOf<Shelf>("""{"anything":1,"$property":$value}""").run {
                path.assert().describedAs(property).isEqualTo(property)
                reason.assert().describedAs(property).isEqualTo(Reason.INVALID_FORMAT)
                message.assert().describedAs(property).startsWith("Not a valid value of its type, given ")
            }
        }
        // A value class over text reads as its text: a number for it is a number for a String.
        failureOf<Shelf>("""{"anything":1,"code":5}""").run {
            path.assert().isEqualTo("code")
            message.assert().isEqualTo("Expected a string, given an integer.")
        }
    }

    @Test
    fun `a failure that is not about a value should be described as none`() {
        val syntax = runCatching { WowJson.mapper.readTree("{not json") }.exceptionOrNull() as JacksonException
        failures.of(WowJson.mapper.readTree("{}"), Order::class.java, syntax).assert().isNull()
    }

    @Test
    fun `a property given twice should be found by its path, and none in JSON that gives none`() {
        fun duplicate(json: String) = failures.duplicateIn(json.toByteArray())
        duplicate("""{"sku":"a","sku":"b"}""")!!.run {
            path.assert().isEqualTo("sku")
            reason.assert().isEqualTo(Reason.DUPLICATE_PROPERTY)
            message.assert().isEqualTo("Given more than once in its object.")
        }
        duplicate("""{"items":[{"sku":"a"},{"sku":"b","quantity":1,"sku":"c"}]}""")!!.path.assert()
            .isEqualTo("items[1].sku")
        duplicate("""{"tags":{"a":1,"a":2}}""")!!.path.assert().isEqualTo("tags.a")
        duplicate("""{"items":[{"sku":"a"},{"sku":"a"}]}""").assert().isNull()
        duplicate("""{"sku":"a",""").assert().isNull()
    }

    @Test
    fun `the request reader should refuse a property given twice`() {
        val reader = failures.requestReader.forType(Item::class.java)
        assertThrows<JacksonException> { reader.readValue<Item>("""{"sku":"a","sku":"b","quantity":1}""") }
        WowJson.mapper.readValue("""{"sku":"a","sku":"b","quantity":1}""", Item::class.java).sku.assert().isEqualTo("b")
    }

    data class Priced(val price: Int, val quantity: Int) {
        val total: Int get() = price * quantity
        val isFree: Boolean get() = price == 0
        var note: String = ""
    }

    /**
     * A getter alone whose value a creator's parameter of its name gives is a property read, not a computed one; a
     * creator's parameter with no getter (`offset`) is read too.
     */
    class Celsius(degrees: Double, offset: Double = 0.0) {
        private val value = degrees + offset
        val degrees: Double get() = value
    }

    @Test
    fun `a reader refusing an unknown property should name it, and read what Wow writes of a computed one`() {
        val reader = failures.requestReader.forType(Priced::class.java)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        val text = WowJson.mapper.writeValueAsString(Priced(2, 3))
        val written = WowJson.mapper.readTree(text).propertyNames()
        written.assert().containsExactlyInAnyOrder("price", "quantity", "total", "isFree", "note")
        reader.readValue<Priced>(text).assert().isEqualTo(Priced(2, 3))
        reader.readValue<Priced>("""{"price":2,"quantity":3,"total":99,"note":"gift"}""").run {
            total.assert().isEqualTo(6)
            note.assert().isEqualTo("gift")
        }
        failures.requestReader.forType(Celsius::class.java).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .readValue<Celsius>("""{"degrees":21.0,"offset":0.5}""").degrees.assert().isEqualTo(21.5)
        val json = """{"price":2,"quantity":3,"Quantity":5}"""
        val failure = assertThrows<JacksonException> { reader.readValue<Priced>(json) }
        failures.of(WowJson.mapper.readTree(json), Priced::class.java, failure)!!.run {
            path.assert().isEqualTo("Quantity")
            reason.assert().isEqualTo(Reason.UNKNOWN_PROPERTY)
            message.assert().isEqualTo("Not a property of its type.")
        }
    }
}
