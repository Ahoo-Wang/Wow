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

package me.ahoo.wow.spi.transport

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.state.PublishedState
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import me.ahoo.wow.tck.ExactJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The Kafka envelope of design 08 §8 (decisions D58, D59) and the state events it carries. */
class EnvelopeCodecTest {
    private val ref = AggregateType("shop", "order").ref("t1", "o-1")
    private val wait = WaitContext(
        "w-1",
        Origin("node-1", "10.0.0.1:9090", "shop-service"),
        listOf(Hop.handled("OrderView.onEvent", "shop")),
        1_000L,
    )

    private fun tree(json: String) = WowJson.mapper.readTree(json)

    private fun record(state: PublishedState? = PublishedState(2, "f1", tree(ExactJson.NUMBERS))) = StateEventRecord(
        ref = ref,
        version = 2,
        commandId = "c-2",
        requestId = "r-2",
        commandType = "shop.order.Pay",
        commitTime = 42L,
        events = listOf(StateEventPayload("shop.order.Paid", 2, tree("""{"amount":2.50}"""))),
        state = state,
        scope = Scope("alice", "space-1"),
        deleted = false,
        tags = mapOf("region" to listOf("eu")),
        causation = Causation("root", "shop.cart/t1/alice@3"),
        headers = mapOf("app" to "web"),
        operator = Operator("alice", "t0", crossScope = true),
        transport = Transport(wait, listOf(ReservationKey("email", "a@b")), mapOf("traceparent" to "00-1-2-01")),
    )

    @Test
    fun `a state event should keep its facts, state and numbers through the wire`() {
        val record = record()
        val read = EnvelopeCodec.stateEventOf(EnvelopeCodec.encode(EnvelopeCodec.stateEvent(record)))
        read.assert().isEqualTo(
            record.copyWith(transport = Transport(wait, trace = mapOf("traceparent" to "00-1-2-01")))
        )
        read.id.assert().isEqualTo("shop.order/t1/o-1@2")
        val state = WowJson.mapper.writeValueAsString(read.state!!.state)
        ExactJson.of(state).assert().isEqualTo(ExactJson.of(ExactJson.NUMBERS))
        WowJson.mapper.writeValueAsString(read.events.single().payload).assert().isEqualTo("""{"amount":2.50}""")
    }

    @Test
    fun `the value should embed the payload and the state as JSON objects`() {
        val value = tree(String(EnvelopeCodec.encode(EnvelopeCodec.stateEvent(record()))))
        value.properties().map { it.key }.assert()
            .containsExactly("format", "kind", "id", "type", "payload", "transport")
        value["format"].intValue().assert().isEqualTo(1)
        value["kind"].stringValue().assert().isEqualTo("state-event")
        value["id"].stringValue().assert().isEqualTo("shop.order/t1/o-1@2")
        value["type"].stringValue().assert().isEqualTo("shop.order")
        val payload = value["payload"]
        payload.properties().map { it.key }.assert().containsExactly(
            "ref", "version", "commandId", "requestId", "commandType", "commitTime", "events", "state", "scope",
            "deleted", "tags", "causation", "headers", "operator",
        )
        payload["operator"].properties().map { it.key }.assert().containsExactly("id", "tenantId", "crossScope")
        payload["events"][0]["payload"]["amount"].isNumber.assert().isTrue()
        payload["state"]["schemaFingerprint"].stringValue().assert().isEqualTo("f1")
        payload["state"]["state"].isObject.assert().isTrue()
        value["transport"].properties().map { it.key }.assert().containsExactly("wait", "trace")
        value["transport"]["wait"]["origin"]["address"].stringValue().assert().isEqualTo("10.0.0.1:9090")
    }

    @Test
    fun `a state event without its state should carry a null state`() {
        val record = record(state = null).copyWith(transport = null)
        val value = tree(String(EnvelopeCodec.encode(EnvelopeCodec.stateEvent(record))))
        value["payload"]["state"].isNull.assert().isTrue()
        value["transport"].isNull.assert().isTrue()
        EnvelopeCodec.stateEventOf(EnvelopeCodec.encode(EnvelopeCodec.stateEvent(record))).assert().isEqualTo(record)
    }

    @Test
    fun `a message should be keyed by the aggregate ref and repeat the envelope in its headers`() {
        val message = EnvelopeCodec.message(record())
        message.key.assert().isEqualTo("shop.order/t1/o-1")
        message.headers.assert().isEqualTo(
            mapOf("wow-format" to "1", "wow-kind" to "state-event", "wow-type" to "shop.order"),
        )
        EnvelopeCodec.stateEventOf(message.value).version.assert().isEqualTo(2)
    }

    @Test
    fun `a message should be written byte for byte as its envelope through the JSON tree`() {
        // D269: message(record) writes the record at once; the wire must not change from the envelope's tree.
        val bare = record(state = null).let {
            StateEventRecord(
                ref = it.ref,
                version = 1,
                commandId = "c-1",
                requestId = "r-1",
                commandType = "shop.order.Place",
                commitTime = 1L,
                events = listOf(StateEventPayload("shop.order.Placed", 1, tree("""{"items":[]}"""))),
            )
        }
        val texts = record(
            state = PublishedState(
                2,
                "f3",
                tree(
                    """{"name":"ü \" é ☃","nested":{"list":[1,2.0,-0.0,1e400,null,true],"big":123456789012345678901234567890.123400},"empty":{}}"""
                ),
            ),
        )
        listOf(record(), record(state = null), bare, texts).forEach { record ->
            val message = EnvelopeCodec.message(record)
            val envelope = EnvelopeCodec.stateEvent(record)
            String(message.value).assert().isEqualTo(String(EnvelopeCodec.encode(envelope)))
            message.headers.assert().isEqualTo(EnvelopeCodec.headers(envelope))
            message.key.assert().isEqualTo(record.ref.toString())
        }
    }

    @Test
    fun `a reader should ignore the fields it does not know`() {
        val json = """{"format":1,"kind":"command","id":"c-1","type":"shop.order.Place","payload":{"a":1},
            |"added":true,"transport":{"trace":{"t":"1"},"later":1}}
        """.trimMargin()
        val envelope = EnvelopeCodec.decode(json.toByteArray())
        envelope.assert().isEqualTo(
            Envelope(
                EnvelopeKind.COMMAND,
                "c-1",
                "shop.order.Place",
                tree("""{"a":1}"""),
                Transport(trace = mapOf("t" to "1"))
            ),
        )
    }

    @Test
    fun `a reader should refuse another format and an unknown kind`() {
        assertThrows<IllegalArgumentException> {
            EnvelopeCodec.decode("""{"format":2,"kind":"command","id":"c","type":"t","payload":{}}""".toByteArray())
        }
        assertThrows<IllegalArgumentException> {
            EnvelopeCodec.decode("""{"format":1,"kind":"other","id":"c","type":"t","payload":{}}""".toByteArray())
        }
        assertThrows<IllegalArgumentException> {
            EnvelopeCodec.stateEventOf(Envelope(EnvelopeKind.COMMAND_RECEIPT, "c", "t", tree("{}")))
        }
    }

    @Test
    fun `the kinds should be named as on the wire`() {
        EnvelopeKind.entries.map { it.toString() }.assert()
            .containsExactly("state-event", "command", "command-receipt")
        EnvelopeKind.of("command-receipt").assert().isEqualTo(EnvelopeKind.COMMAND_RECEIPT)
        EnvelopeKind.of("unknown").assert().isNull()
    }

    @Test
    fun `a record should hold a consistent commit`() {
        assertThrows<IllegalArgumentException> { record(state = PublishedState(3, "f1", tree("{}"))) }
        assertThrows<IllegalArgumentException> { record().copyWith(events = emptyList()) }
        assertThrows<IllegalArgumentException> { record().copyWith(version = 0) }
        assertThrows<IllegalArgumentException> { record().copyWith(commandId = " ") }
        assertThrows<IllegalArgumentException> { record().copyWith(commandType = "") }
        assertThrows<IllegalArgumentException> { PublishedState(0, "f1", tree("{}")) }
        assertThrows<IllegalArgumentException> { PublishedState(1, " ", tree("{}")) }
        assertThrows<IllegalArgumentException> { StateEventPayload("", 1, tree("{}")) }
        assertThrows<IllegalArgumentException> { StateEventPayload("e", 0, tree("{}")) }
        assertThrows<IllegalArgumentException> { Envelope(EnvelopeKind.COMMAND, " ", "t", tree("{}")) }
        assertThrows<IllegalArgumentException> { Envelope(EnvelopeKind.COMMAND, "c", "", tree("{}")) }
        assertThrows<IllegalArgumentException> { Envelope(EnvelopeKind.COMMAND, "c", "t", tree("{}"), format = 0) }
        assertThrows<IllegalArgumentException> { PartitionOffsets.of(2, 1) }
    }
}
