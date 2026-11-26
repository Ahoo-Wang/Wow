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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.wire.CallHeader
import me.ahoo.wow.api.wire.CallWire
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.core.JacksonException

/**
 * The `Wow-Call` header's value of a [CallWire] ([CallHeader]): the base64url of its JSON in Wow's one
 * JSON configuration, which the server's call routes read and `wow-client` writes. A member of the
 * JSON that is not the wire form's is dropped as it is parsed, and a hop's stage the reader does not know reads as
 * [me.ahoo.wow.api.result.Stage.UNKNOWN] ([WowJson.readerBuilder]): the internal port keeps reading what a peer of the
 * next minor version sends. A call of another format ([CallWire.format]) is refused, never read as this one.
 */
@InternalWowApi
object CallWires {
    /** [wire] as the header's value. */
    @JvmStatic
    fun encode(wire: CallWire): String = CallHeader.encode(WowJson.mapper.writeValueAsBytes(wire))

    /** The wire form [value] carries; an [IllegalArgumentException] when it does not parse or is of another format. */
    @JvmStatic
    fun decode(value: String): CallWire {
        val bytes = CallHeader.decode(value)
        val wire = try {
            WowJson.reader.readValue(bytes, CallWire::class.java)
        } catch (invalid: JacksonException) {
            throw IllegalArgumentException("Wow-Call is not a call: ${invalid.originalMessage}", invalid)
        }
        require(wire.format == CallWire.FORMAT) {
            "Wow-Call is of format ${wire.format}; this node reads format ${CallWire.FORMAT}."
        }
        return wire
    }
}
