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
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
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
}
