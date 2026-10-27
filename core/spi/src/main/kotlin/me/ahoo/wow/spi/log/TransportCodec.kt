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

import com.fasterxml.jackson.annotation.JsonInclude
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.wire.WaitWire
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.wait.toContext
import me.ahoo.wow.spi.wait.toWire
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleModule

/**
 * The one form of a [Transport]: the runtime encodes it once, the log
 * stores the bytes as they are, and the Kafka envelope and the cluster channel embed the same JSON object.
 *
 * `{"wait":{"waitId","origin":{"node","address","service"},"remaining":[{"stage","context","subscription"}],
 * "deadline"},"reservations":[{"namespace","key","system"}],"trace":{…},"releases":[…]}`: an absent wait, empty lists
 * and an empty trace are left out, and so is a key's `system` when false. The bytes are UTF-8 JSON. A reader ignores the fields it does not know,
 * so the next minor version may add one within format 1; a backend never reads the bytes, so a field
 * it does not know reaches the runtime that reads the entry unchanged.
 *
 * The wait context is the wire record every Wow wire carries it as ([WaitWire]), so its form is defined once.
 *
 * [WowJson]'s mapper writes and reads a [Transport] in this form wherever a message embeds one ([module]).
 */
@InternalWowApi
object TransportCodec {
    /** [transport] as the bytes a log stores. */
    fun encode(transport: Transport): ByteArray = WowJson.mapper.writeValueAsBytes(TransportWire.of(transport))

    /** The transport [bytes] hold. */
    fun decode(bytes: ByteArray): Transport = WowJson.mapper.readValue(bytes, TransportWire::class.java).toTransport()

    /** Writes and reads a [Transport] as [TransportWire]: part of every mapper [WowJson] builds. */
    internal val module: SimpleModule = SimpleModule("wow-transport")
        .addSerializer(Transport::class.java, Writer)
        .addDeserializer(Transport::class.java, Reader)

    private object Writer : ValueSerializer<Transport>() {
        override fun serialize(value: Transport, gen: JsonGenerator, ctxt: SerializationContext) {
            ctxt.writeValue(gen, TransportWire.of(value))
        }
    }

    private object Reader : ValueDeserializer<Transport>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Transport =
            ctxt.readValue(p, TransportWire::class.java).toTransport()
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private data class TransportWire(
        val wait: WaitWire? = null,
        val reservations: List<KeyWire> = emptyList(),
        val trace: Map<String, String> = emptyMap(),
        val releases: List<KeyWire> = emptyList(),
    ) {
        fun toTransport(): Transport = Transport(
            wait = wait?.toContext(),
            reservations = reservations.map(KeyWire::toKey),
            trace = trace,
            releases = releases.map(KeyWire::toKey),
        )

        companion object {
            fun of(transport: Transport): TransportWire = TransportWire(
                wait = transport.wait?.toWire(),
                reservations = transport.reservations.map(KeyWire::of),
                trace = transport.trace,
                releases = transport.releases.map(KeyWire::of),
            )
        }
    }

    private data class KeyWire(
        val namespace: String,
        val key: String,
        @field:JsonInclude(JsonInclude.Include.NON_DEFAULT) val system: Boolean = false,
    ) {
        fun toKey(): ReservationKey = ReservationKey(namespace, key, system)

        companion object {
            fun of(key: ReservationKey): KeyWire = KeyWire(key.namespace, key.key, key.system)
        }
    }
}
