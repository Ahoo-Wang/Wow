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

import me.ahoo.wow.spi.log.Transport
import tools.jackson.databind.JsonNode

/** What an [Envelope] carries (design 08 §8), by its [wire] name, the `kind` of the value and the `wow-kind` header. */
enum class EnvelopeKind(val wire: String) {
    /** A [me.ahoo.wow.spi.state.StateEventRecord]: `id` is `ref@version`, `type` the aggregate type. */
    STATE_EVENT("state-event"),

    /** A command in its wire form (design 08 §3): `id` is the command id, `type` the command type. */
    COMMAND("command"),

    /** The result of a cross-context command (decisions D47, D136): `id` is the command id. */
    COMMAND_RECEIPT("command-receipt"),
    ;

    override fun toString(): String = wire

    companion object {
        /** The kind named [wire], or null when this version knows none of that name. */
        fun of(wire: String): EnvelopeKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A message of a Kafka topic (design 08 §8, decision D58): `{format, kind, id, type, payload, transport}`.
 *
 * - [format] is the envelope's format version (decision D59). A field added with a default does not change it; a
 *   reader refuses a format it does not know, which only a major version brings.
 * - [id] is what a reader deduplicates by: delivery is at least once (decision D45).
 * - [type] names what [payload] is: the aggregate type of a state event (`context.aggregate`), the command type of a
 *   command (`context.aggregate.Command`).
 * - [payload] is an embedded JSON object, never a string holding one, so a reader takes values by path and a person
 *   reads it as it is.
 * - [transport] is the delivery metadata (decision D25): the wait context and the trace, never a fact.
 *
 * The Kafka key is not in the envelope: it is the aggregate ref (`context.aggregate/tenant/id`), so the messages of an
 * aggregate keep their order in one partition. The headers repeat [format], [kind] and [type] ([EnvelopeHeaders]),
 * so a reader filters without parsing the value.
 */
data class Envelope(
    val kind: EnvelopeKind,
    val id: String,
    val type: String,
    val payload: JsonNode,
    val transport: Transport? = null,
    val format: Int = FORMAT,
) {
    init {
        require(id.isNotBlank()) { "An envelope has an id." }
        require(type.isNotBlank()) { "An envelope names the type of its payload." }
        require(format >= 1) { "An envelope's format starts at 1." }
    }

    companion object {
        /** The envelope format this version writes and reads. */
        const val FORMAT: Int = 1
    }
}

/** The headers of a Kafka message, each repeating a field of its [Envelope]. */
object EnvelopeHeaders {
    /** [Envelope.format], as a decimal number. */
    const val FORMAT: String = "wow-format"

    /** [Envelope.kind], by its wire name. */
    const val KIND: String = "wow-kind"

    /** [Envelope.type]. */
    const val TYPE: String = "wow-type"
}
