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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.spi.log.Transport
import tools.jackson.databind.JsonNode

/**
 * What an [Envelope] carries, by its [wire] name ([WireNames]), the `kind` of the value and the `wow-kind` header:
 * `STATE_EVENT` is `state-event`.
 */
enum class EnvelopeKind {
    /** A [me.ahoo.wow.spi.state.StateEventRecord]: `id` is `ref@version`, `type` the aggregate type. */
    STATE_EVENT,

    /** A command in its wire form: `id` is the command id, `type` the command type. */
    COMMAND,

    /** The result of a cross-context command: `id` is the command id. */
    COMMAND_RECEIPT,
    ;

    /** This kind's wire name. */
    val wire: String get() = WireNames.of(this)

    override fun toString(): String = wire

    companion object {
        /** The kind named [wire], or null when this version knows none of that name. */
        fun of(wire: String): EnvelopeKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A message of a Kafka topic: `{format, kind, id, type, payload, transport}`.
 *
 * - [format] is the envelope's format version. A field added with a default does not change it; a
 *   reader refuses a format it does not know, which only a major version brings.
 * - [id] is what a reader deduplicates by: delivery is at least once.
 * - [type] names what [payload] is: the aggregate type of a state event (`context.aggregate`), the command type of a
 *   command (`context.aggregate.Command`).
 * - [payload] is an embedded JSON object, never a string holding one, so a reader takes values by path and a person
 *   reads it as it is.
 * - [transport] is the delivery metadata: the wait context and the trace, never a fact.
 *
 * The Kafka key is not in the envelope: it is the aggregate ref (`context.aggregate/tenant/id`), so the messages of an
 * aggregate keep their order in one partition. The headers repeat [format], [kind] and [type] ([EnvelopeHeaders]),
 * so a reader filters without parsing the value.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class Envelope @InternalWowApi constructor(
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

    override fun equals(other: Any?): Boolean =
        this === other || other is Envelope && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "Envelope(kind=$kind, id=$id, type=$type, payload=$payload, transport=$transport, format=$format)"

    private fun valueFields(): List<Any?> = listOf(kind, id, type, payload, transport, format)

    companion object {
        /** The envelope format this version writes and reads. */
        const val FORMAT: Int = 1

        /** An Envelope of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            kind: EnvelopeKind,
            id: String,
            type: String,
            payload: JsonNode,
            transport: Transport? = null,
            format: Int = FORMAT,
        ): Envelope = Envelope(kind, id, type, payload, transport, format)
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
