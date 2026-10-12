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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.spi.log.EventRecord
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.BigInteger

class EventUpgradeTest {
    /** Revision 3 of an event that began as `{"name": "..."}` and then split the name and added a source. */
    data class Renamed(val first: String, val last: String, val source: String)

    class Person(val id: String)

    private val model = aggregate<Person>("test", "person") {
        initial { Person(it.id) }
        evolve<Renamed>(revision = 3) { }
        alias<Renamed>("test.person.NameChanged")
        upgrade<Renamed>(from = 1) { payload ->
            val node = payload as ObjectNode
            val (first, last) = node.remove("name").asString().split(" ")
            node.put("first", first).put("last", last)
        }
        upgrade<Renamed>(from = 2) { payload -> (payload as ObjectNode).put("source", "unknown") }
    }
    private val codec = EventCodec()

    /** Revision 2 of an event that began as `{"amount": ...}` and then named its currency. */
    data class Priced(
        val amount: BigDecimal,
        val currency: String,
        val count: BigInteger,
        val ratio: Double,
        val extra: Map<String, Any?>,
    )

    private val priced = aggregate<Person>("test", "priced") {
        initial { Person(it.id) }
        evolve<Priced>(revision = 2) { }
        upgrade<Priced>(from = 1) { payload -> (payload as ObjectNode).put("currency", "CNY") }
    }

    private fun pricedPayload(amount: String, count: String, ratio: String, extra: String = "{}") =
        """{"amount":$amount,"count":$count,"ratio":$ratio,"extra":$extra}"""

    private fun decodePriced(revision: Int, payload: String) =
        codec.decode(priced, EventRecord("test.priced.Priced", revision, payload)) as Priced

    @Test
    fun `an upgrade keeps the scale and every digit of a decimal`() {
        val longDecimal = "123456789012345678901234567890.123456789012345678901234567890"
        val bigInteger = "123456789012345678901234567890123456789"
        val upgraded = decodePriced(1, pricedPayload("2.50", bigInteger, "0.1"))
        upgraded.amount.assert().isEqualTo(BigDecimal("2.50"))
        upgraded.amount.scale().assert().isEqualTo(2)
        upgraded.count.assert().isEqualTo(BigInteger(bigInteger))
        upgraded.ratio.assert().isEqualTo(0.1)
        upgraded.currency.assert().isEqualTo("CNY")

        val longAmount = decodePriced(1, pricedPayload(longDecimal, "1", "1e-3"))
        longAmount.amount.assert().isEqualTo(BigDecimal(longDecimal))
        longAmount.ratio.assert().isEqualTo(0.001)
    }

    @Test
    fun `an upgraded payload binds as a current one does`() {
        val extra = """{"price":2.50,"long":123456789012345678901234567890.5,"count":12345678901234567890123}"""
        val payload = pricedPayload("2.50", "7", "0.25", extra)
        val current = decodePriced(2, """{"currency":"CNY",""" + payload.drop(1))
        decodePriced(1, payload).assert().isEqualTo(current)
        // Both read an untyped decimal exactly, as Wow's mapper does (decision D122).
        current.extra.assert().isEqualTo(
            mapOf(
                "price" to BigDecimal("2.50"),
                "long" to BigDecimal("123456789012345678901234567890.5"),
                "count" to BigInteger("12345678901234567890123"),
            ),
        )
    }

    @Test
    fun `an old revision is upgraded one revision at a time`() {
        codec.decode(model, EventRecord("test.person.Renamed", 1, """{"name":"Ada Lovelace"}"""))
            .assert().isEqualTo(Renamed("Ada", "Lovelace", "unknown"))
        codec.decode(model, EventRecord("test.person.Renamed", 2, """{"first":"Ada","last":"Byron"}"""))
            .assert().isEqualTo(Renamed("Ada", "Byron", "unknown"))
    }

    @Test
    fun `a record under a former name is still read`() {
        codec.decode(model, EventRecord("test.person.NameChanged", 3, """{"first":"A","last":"B","source":"s"}"""))
            .assert().isEqualTo(Renamed("A", "B", "s"))
    }

    @Test
    fun `a newer revision than the model reads fails`() {
        assertThrownBy<IllegalStateException> {
            codec.decode(model, EventRecord("test.person.Renamed", 4, "{}"))
        }
    }

    @Test
    fun `an event type missing an upgrade does not build`() {
        assertThrownBy<IllegalArgumentException> {
            aggregate<Person>("test", "person") {
                initial { Person(it.id) }
                evolve<Renamed>(revision = 3) { }
                upgrade<Renamed>(from = 1) { it }
            }
        }
    }
}
