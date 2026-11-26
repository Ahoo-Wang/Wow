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

package me.ahoo.wow.spi.wire

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.wire.CallHeader
import me.ahoo.wow.api.wire.CallWire
import me.ahoo.wow.api.wire.WaitWire
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode

/** The `Wow-Call` header's wire form: what a peer of the next minor version sends is read. */
class CallWiresTest {
    private val wire = CallWire(
        commandId = "c-1",
        tenantId = "t1",
        causation = Causation("r-1", "e-1"),
        wait = WaitWire(
            "w-1",
            WaitWire.Origin("n-1", "10.0.0.1:9090", "shop-service"),
            listOf(Hop.handled("OrderView.onEvent", "shop"), Hop.committed("billing")),
            1_000L,
        ),
    )

    @Test
    fun `a call should come back as it was sent`() {
        CallWires.decode(CallWires.encode(wire)).assert().isEqualTo(wire)
    }

    @Test
    fun `a hop's stage a newer peer added should read as UNKNOWN, its subscription kept`() {
        val newer = WowJson.mapper.readTree(CallHeader.decode(CallWires.encode(wire))) as ObjectNode
        (newer["wait"]["remaining"][0] as ObjectNode).put("stage", "delivered")
        val read = CallWires.decode(CallHeader.encode(WowJson.mapper.writeValueAsBytes(newer)))
        val hops = checkNotNull(read.wait).remaining
        hops.map { it.stage }.assert().containsExactly(Stage.UNKNOWN, Stage.COMMITTED)
        hops.first().subscription.assert().isEqualTo("OrderView.onEvent")
    }

    @Test
    fun `a call should carry its format, and one of another format should be refused, never read as this one`() {
        val sent = WowJson.mapper.readTree(CallHeader.decode(CallWires.encode(wire))) as ObjectNode
        sent["format"].asInt().assert().isEqualTo(CallWire.FORMAT)
        sent.put("format", 2)
        assertThrows<IllegalArgumentException> {
            CallWires.decode(CallHeader.encode(WowJson.mapper.writeValueAsBytes(sent)))
        }.message.assert().isEqualTo("Wow-Call is of format 2; this node reads format 1.")
    }

    @Test
    fun `a value that is no call should be refused`() {
        assertThrows<IllegalArgumentException> { CallWires.decode(CallHeader.encode("[]".toByteArray())) }
    }
}
