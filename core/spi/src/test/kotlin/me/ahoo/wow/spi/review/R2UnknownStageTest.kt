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

package me.ahoo.wow.spi.review

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.log.TransportCodec
import me.ahoo.wow.spi.transport.Envelope
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.EnvelopeKind
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * Review r2 (wire and storage formats): `Stage` says "Cases may be added in a minor release", and its `UNKNOWN` and
 * `operate/upgrades.md` ("Two versions side by side") say Wow's nodes read a stage a newer writer added as `UNKNOWN`.
 * A transport (the wait context a log entry stores, and the Kafka envelope and the cluster channel embed) is read with
 * `WowJson.mapper`, whose enumerations are strict, not with `WowJson.readerBuilder()`, which the cluster channel's own
 * mapper and the client use. A hop of a stage a 10.1 node added, in a log entry or an envelope a 10.0 node reads during
 * a rolling upgrade, throws instead.
 */
class R2UnknownStageTest {
    private val transport = Transport(
        wait = WaitContext(
            "w-1",
            Origin("node-1", "10.0.0.1:9090", "bank-service"),
            listOf(Hop.handled("bank.projector", "bank"), Hop.snapshot()),
            1_000L,
        ),
    )

    /** The JSON of [transport] with its first hop's stage a value a newer writer added. */
    private fun newer(): ObjectNode = (WowJson.mapper.readTree(TransportCodec.encode(transport)) as ObjectNode).also {
        ((it["wait"]["remaining"][0]) as ObjectNode).put("stage", "delivered")
    }

    @Test
    fun `a log entry's transport holding a stage a newer writer added should read it as UNKNOWN`() {
        val read = TransportCodec.decode(WowJson.mapper.writeValueAsBytes(newer()))
        checkNotNull(read.wait).remaining.first().stage.assert().isEqualTo(Stage.UNKNOWN)
    }

    @Test
    fun `an envelope's transport holding a stage a newer writer added should read it as UNKNOWN`() {
        val envelope = Envelope(EnvelopeKind.STATE_EVENT, "r@1", "bank.account", WowJson.mapper.createObjectNode())
        val tree = WowJson.mapper.readTree(EnvelopeCodec.encode(envelope)) as ObjectNode
        tree.set("transport", newer())
        val read = EnvelopeCodec.decode(WowJson.mapper.writeValueAsBytes(tree))
        checkNotNull(checkNotNull(read.transport).wait).remaining.first().stage.assert().isEqualTo(Stage.UNKNOWN)
    }
}
