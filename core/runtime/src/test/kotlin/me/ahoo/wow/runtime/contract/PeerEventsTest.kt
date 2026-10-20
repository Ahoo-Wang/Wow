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

package me.ahoo.wow.runtime.contract

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.subscribe.PublishedEvent
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.spi.state.StateEventPayload
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** DX-1c: another context's published events decoded into its api classes at the contract's revision. */
class PeerEventsTest {
    data class Paid(val amount: Int)

    private val json = JsonMapper.builder().build()
    private val codec = EventCodec()

    private val payment = ContextManifest(
        "payment",
        aggregates = listOf(
            AggregateManifest(
                name = "payment",
                state = StateManifest(Manifest.schemaRef("sample.PaymentState")),
                events = listOf(
                    EventManifest("Paid", revision = 2, schema = Manifest.schemaRef(Paid::class.java.name)),
                    EventManifest("Refunded", schema = Manifest.schemaRef("sample.NotOnTheClassPath")),
                ),
            ),
        ),
    )

    private val events = PeerEvents(listOf(payment))

    private fun payload(name: String, revision: Int, body: String) =
        StateEventPayload.of(name, revision, json.readTree(body))

    @Test
    fun `an event of the contract's revision should be its api class, any other a PublishedEvent`() {
        val decoded = events.decode(
            listOf(
                payload("payment.payment.Paid", 2, """{"amount":3}"""),
                payload("payment.payment.Paid", 1, """{"cents":300}"""),
                payload("payment.payment.Refunded", 1, """{}"""),
                payload("payment.payment.Unknown", 1, """{"a":1}"""),
                "already decoded",
            ),
            codec,
        )
        decoded[0].assert().isEqualTo(Paid(3))
        // Another revision than the api's is not its class.
        decoded[1].assert().isEqualTo(PublishedEvent.of("payment.payment.Paid", 1, """{"cents":300}"""))
        // No class on the class path, or no event the contract declares.
        decoded[2].assert().isEqualTo(PublishedEvent.of("payment.payment.Refunded", 1, "{}"))
        decoded[3].assert().isEqualTo(PublishedEvent.of("payment.payment.Unknown", 1, """{"a":1}"""))
        decoded[4].assert().isEqualTo("already decoded")
        PeerEvents.NONE.decode(listOf(payload("payment.payment.Paid", 2, "{}")), codec).single().assert()
            .isInstanceOf(PublishedEvent::class.java)
    }

    @Test
    fun `a payload its class does not decode should fail the handling`() {
        assertThrownBy<Exception> {
            events.decode(listOf(payload("payment.payment.Paid", 2, """{"amount":"many"}""")), codec)
        }
    }

    @Test
    fun `a published event should have value semantics`() {
        val event = PublishedEvent.of("a.b.C", 1, "{}")
        event.assert().isEqualTo(PublishedEvent.of("a.b.C", 1, "{}"))
        event.hashCode().assert().isEqualTo(PublishedEvent.of("a.b.C", 1, "{}").hashCode())
        event.assert().isNotEqualTo(PublishedEvent.of("a.b.C", 2, "{}"))
        event.toString().assert().isEqualTo("PublishedEvent(name=a.b.C, revision=1, payload={})")
        assertThrownBy<IllegalArgumentException> { PublishedEvent.of(" ", 1, "{}") }
        assertThrownBy<IllegalArgumentException> { PublishedEvent.of("a.b.C", 0, "{}") }
    }
}
