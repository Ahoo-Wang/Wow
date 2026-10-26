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

package me.ahoo.wow.spi.log

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/** The one form of a transport (finding R5.B-3): the bytes a log stores and the object a message embeds. */
class TransportCodecTest {
    private val wait = WaitContext(
        "w-1",
        Origin("node-1", "10.0.0.1:9090", "bank-service"),
        listOf(Hop.handled("bank.projector", "bank"), Hop.snapshot()),
        1_000L,
    )
    private val full = Transport(
        wait = wait,
        reservations = listOf(ReservationKey("email", "a@b ü"), ReservationKey("login", "x", system = true)),
        trace = mapOf("traceparent" to "00-1-2-01"),
        releases = listOf(ReservationKey("email", "old")),
    )

    @Test
    fun `a transport round-trips through its bytes`() {
        TransportCodec.decode(TransportCodec.encode(full)).assert().isEqualTo(full)
        TransportCodec.decode(TransportCodec.encode(Transport())).assert().isEqualTo(Transport())
    }

    @Test
    fun `absent and empty parts are left out, and a key's system flag only when set`() {
        TransportCodec.encode(Transport()).decodeToString().assert().isEqualTo("{}")
        TransportCodec.encode(Transport(trace = mapOf("t" to "1"))).decodeToString()
            .assert().isEqualTo("""{"trace":{"t":"1"}}""")
        val tree = WowJson.mapper.readTree(TransportCodec.encode(full))
        tree.properties().map { it.key }.assert().containsExactly("wait", "reservations", "trace", "releases")
        tree["wait"].properties().map { it.key }.assert().containsExactly("waitId", "origin", "remaining", "deadline")
        tree["reservations"][0].has("system").assert().isFalse()
        tree["reservations"][1]["system"].booleanValue().assert().isTrue()
    }

    @Test
    fun `a field a newer version adds is ignored by this one, at every level`() {
        val newer = WowJson.mapper.readTree(TransportCodec.encode(full)) as ObjectNode
        newer.put("priority", 7)
        val wait = newer["wait"] as ObjectNode
        wait.put("hops", 3)
        (wait["origin"] as ObjectNode).put("zone", "z")
        (wait["remaining"][0] as ObjectNode).put("group", "g")
        (newer["reservations"][0] as ObjectNode).put("ttl", 5)
        TransportCodec.decode(WowJson.mapper.writeValueAsBytes(newer)).assert().isEqualTo(full)
    }

    @Test
    fun `Wow's mapper embeds a transport in the same form`() {
        val embedded = WowJson.mapper.writeValueAsBytes(mapOf("transport" to full))
        val expected = """{"transport":${TransportCodec.encode(full).decodeToString()}}"""
        embedded.decodeToString().assert().isEqualTo(expected)
        WowJson.mapper.readValue(TransportCodec.encode(full), Transport::class.java).assert().isEqualTo(full)
    }
}
