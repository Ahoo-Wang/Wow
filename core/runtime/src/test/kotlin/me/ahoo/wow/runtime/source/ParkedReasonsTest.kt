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

package me.ahoo.wow.runtime.source

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicMessage
import org.junit.jupiter.api.Test

/**
 * #6588: an aggregate parked for a value it could not take records why without the value: the probe's cases of a
 * topic's message whose envelope holds a card number or an e-mail address where a number goes, and of a handler whose
 * event does not read into its class.
 */
class ParkedReasonsTest : TopicSubscriptionsFixtures() {
    data class Card(val holder: String, val digits: Int)

    /** The reason [id] parks with when its message is a real envelope of its record that [mutate] changes. */
    private suspend fun parkedFor(id: String, mutate: (String) -> String): String {
        start("node-1")
        val body = mutate(String(EnvelopeCodec.message(orderRecord(id, 1)).value))
        topics.send(ORDER_TOPIC, TopicMessage.of(order(id).toString(), body.toByteArray()))
        eventually { ledger.parked(BOARD_ID, order(id)) != null }
        return ledger.parked(BOARD_ID, order(id))!!.reason
    }

    @Test
    fun `a message whose envelope holds a card number where a number goes should park without the number`(): Unit =
        runBlocking {
            val reason = parkedFor("o-3") {
                it.replaceFirst(Regex("\"version\":\\d+"), "\"version\":\"4111 1111 1111 1111\"")
            }
            reason.assert().isEqualTo(
                "me.ahoo.wow.runtime.subscription.UndecodableEntryException: not decodable by this version; " +
                    "caused by tools.jackson.databind.exc.MismatchedInputException: cannot read 'version' as int",
            )
        }

    @Test
    fun `a message whose envelope holds an e-mail address where a time goes should park without the address`(): Unit =
        runBlocking {
            val reason = parkedFor("o-4") {
                it.replace("\"commitTime\":1000", "\"commitTime\":\"alice.smith@example.com\"")
            }
            reason.assert().doesNotContain("alice").endsWith("cannot read 'commitTime' as long")
        }

    @Test
    fun `a handler whose event does not read into its class should park without the value`(): Unit = runBlocking {
        val decoding = Subscription(BOARD, SubscriptionFilter(ORDER.context, setOf(ORDER.name))) { _ ->
            // As EventCodec.decodeTree reads an event's body into the handler's own class.
            WowJson.mapper.treeToValue(
                WowJson.mapper.readTree("""{"holder":"Alice Smith","digits":"4111 1111 1111 1111"}"""),
                Card::class.java,
            )
        }
        start("node-1", subscription = decoding)
        topics.publish(orderRecord("o-9", 1))
        eventually { ledger.parked(BOARD_ID, order("o-9")) != null }
        ledger.parked(BOARD_ID, order("o-9"))!!.reason.assert()
            .isEqualTo("tools.jackson.databind.exc.MismatchedInputException: cannot read 'digits' as int")
    }
}
