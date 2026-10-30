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

import com.fasterxml.jackson.annotation.JsonInclude
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.state.PublishedState
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import tools.jackson.databind.JsonNode

/**
 * Reads and writes [Envelope]s as the JSON value of a Kafka message, with Wow's
 * mapper ([WowJson]): every number of a payload or a state keeps its exact value and scale, and the
 * modules are fixed, so the wire form does not depend on what a host brings.
 *
 * - **Value**: `{"format":1,"kind":"state-event","id":…,"type":…,"payload":{…},"transport":{…}}`; `transport` is
 *   null when there is none, and holds the `wait` context and the `trace` only, in the one form of a transport
 *   ([me.ahoo.wow.spi.log.TransportCodec]): the reservations of a log entry stay in the
 *   context.
 * - **Reading**: unknown fields are ignored at every level, so this version reads what the next minor one writes;
 * a format other than [Envelope.FORMAT], or a kind this version does not know, is refused with an
 *   [IllegalArgumentException].
 * - **State events**: [stateEvent] and [stateEventOf] turn a [StateEventRecord] into the envelope of kind
 *   [EnvelopeKind.STATE_EVENT] and back: `id` is `ref@version`, `type` the aggregate type, `payload`
 *   `{ref, version, commandId, requestId, commandType, commitTime, events, state, scope, deleted, tags, causation,
 *   headers, operator, imported}` (`operator` and `imported`, additions within format 1; `imported` only when true)
 *   with each event `{name, revision, payload}` and the state `{version, schemaFingerprint, state}` or null.
 */
object EnvelopeCodec {
    private val mapper = WowJson.mapper

    /** [envelope] as the bytes of a message value. */
    fun encode(envelope: Envelope): ByteArray = mapper.writeValueAsBytes(
        EnvelopeWire(
            format = envelope.format,
            kind = envelope.kind.wire,
            id = envelope.id,
            type = envelope.type,
            payload = envelope.payload,
            transport = envelope.transport?.let(::delivered),
        ),
    )

    /** The envelope [bytes] hold. */
    fun decode(bytes: ByteArray): Envelope {
        val wire = mapper.readValue(bytes, EnvelopeWire::class.java)
        require(wire.format == Envelope.FORMAT) {
            "Envelope format ${wire.format} is not the format ${Envelope.FORMAT} this version reads."
        }
        val kind = requireNotNull(EnvelopeKind.of(wire.kind)) { "Unknown envelope kind ${wire.kind}." }
        return Envelope(kind, wire.id, wire.type, wire.payload, wire.transport, wire.format)
    }

    /** The headers of a message holding [envelope] ([EnvelopeHeaders]). */
    fun headers(envelope: Envelope): Map<String, String> = mapOf(
        EnvelopeHeaders.FORMAT to envelope.format.toString(),
        EnvelopeHeaders.KIND to envelope.kind.wire,
        EnvelopeHeaders.TYPE to envelope.type,
    )

    /** The message of [envelope] under [key]: its value encoded, its headers. */
    fun message(key: String, envelope: Envelope): TopicMessage = TopicMessage(key, encode(envelope), headers(envelope))

    /**
     * The message of [record]: keyed by its aggregate ref, its value the bytes of [stateEvent]'s
     * envelope, written from the record at once rather than through the envelope's JSON tree.
     */
    fun message(record: StateEventRecord): TopicMessage {
        val type = record.ref.type.toString()
        val value = mapper.writeValueAsBytes(
            StateEventEnvelopeWire(
                format = Envelope.FORMAT,
                kind = EnvelopeKind.STATE_EVENT.wire,
                id = record.id,
                type = type,
                payload = wireOf(record),
                transport = record.transport?.let(::delivered),
            ),
        )
        val headers = mapOf(
            EnvelopeHeaders.FORMAT to Envelope.FORMAT.toString(),
            EnvelopeHeaders.KIND to EnvelopeKind.STATE_EVENT.wire,
            EnvelopeHeaders.TYPE to type,
        )
        return TopicMessage(record.ref.toString(), value, headers)
    }

    /** The envelope of [record]. */
    fun stateEvent(record: StateEventRecord): Envelope = Envelope(
        kind = EnvelopeKind.STATE_EVENT,
        id = record.id,
        type = record.ref.type.toString(),
        payload = mapper.valueToTree(wireOf(record)),
        transport = record.transport,
    )

    private fun wireOf(record: StateEventRecord) = StateEventWire(
        ref = record.ref,
        version = record.version,
        commandId = record.commandId,
        requestId = record.requestId,
        commandType = record.commandType,
        commitTime = record.commitTime,
        events = record.events,
        state = record.state,
        scope = record.scope,
        deleted = record.deleted,
        tags = record.tags,
        causation = record.causation,
        headers = record.headers,
        operator = record.operator,
        imported = record.imported,
    )

    /** The record [envelope] holds, with the envelope's transport. */
    fun stateEventOf(envelope: Envelope): StateEventRecord {
        require(envelope.kind == EnvelopeKind.STATE_EVENT) { "A ${envelope.kind} envelope holds no state event." }
        val wire = mapper.treeToValue(envelope.payload, StateEventWire::class.java)
        return StateEventRecord(
            ref = wire.ref,
            version = wire.version,
            commandId = wire.commandId,
            requestId = wire.requestId,
            commandType = wire.commandType,
            commitTime = wire.commitTime,
            events = wire.events,
            state = wire.state,
            scope = wire.scope,
            deleted = wire.deleted,
            tags = wire.tags,
            causation = wire.causation,
            headers = wire.headers,
            operator = wire.operator,
            transport = envelope.transport,
            imported = wire.imported,
        )
    }

    /** The record the message value [bytes] hold. */
    fun stateEventOf(bytes: ByteArray): StateEventRecord = stateEventOf(decode(bytes))

    private data class EnvelopeWire(
        val format: Int,
        val kind: String,
        val id: String,
        val type: String,
        val payload: JsonNode,
        val transport: Transport? = null,
    )

    /** [EnvelopeWire] with the state event's payload as itself, for writing: the same fields, in order. */
    private data class StateEventEnvelopeWire(
        val format: Int,
        val kind: String,
        val id: String,
        val type: String,
        val payload: StateEventWire,
        val transport: Transport? = null,
    )

    /** What of [transport] a message carries: the wait context and the trace, never a log entry's reservations. */
    private fun delivered(transport: Transport): Transport = Transport(wait = transport.wait, trace = transport.trace)

    private data class StateEventWire(
        val ref: AggregateRef,
        val version: Int,
        val commandId: String,
        val requestId: String,
        val commandType: String,
        val commitTime: Long,
        val events: List<StateEventPayload>,
        val state: PublishedState? = null,
        val scope: Scope = Scope.NONE,
        val deleted: Boolean = false,
        val tags: Map<String, List<String>> = emptyMap(),
        val causation: Causation? = null,
        val headers: Map<String, String> = emptyMap(),
        val operator: Operator? = null,
        // Written only when true, so a commit V10 made keeps the payload it had before the field.
        @field:JsonInclude(JsonInclude.Include.NON_DEFAULT) val imported: Boolean = false,
    )
}
