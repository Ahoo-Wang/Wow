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

package me.ahoo.wow.spi.state

import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.core.json.JsonGeneratorBase
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleModule

/**
 * How Wow's JSON writes and reads a state event's [StateEventPayload] and [PublishedState]: `{name, revision, payload}`
 * and `{version, schemaFingerprint, state}`, the fields in that order, as the envelope of a state event carries them.
 *
 * One made from its JSON text ([StateEventPayload.ofText], [PublishedState.ofText]) is written with that text as it is
 * ([JsonGenerator.writeRawValue]) when the generator writes JSON text to an output ([JsonGeneratorBase]): the codec's
 * own JSON, which the node holds already, is neither parsed nor written again. Any other generator gets the tree: a
 * token buffer (`valueToTree`, `convertValue`) would keep the text as an opaque raw value where its reader expects the
 * payload's object, and a binary format has no JSON text to embed. One made from a tree, as a received envelope's is,
 * is written from the tree, as before. A read always makes them from trees.
 */
internal object StateEventJson {
    val module: SimpleModule = SimpleModule("wow-state-event")
        .addSerializer(StateEventPayload::class.java, PayloadWriter)
        .addDeserializer(StateEventPayload::class.java, PayloadReader)
        .addSerializer(PublishedState::class.java, StateWriter)
        .addDeserializer(PublishedState::class.java, StateReader)

    private const val NAME = "name"
    private const val REVISION = "revision"
    private const val PAYLOAD = "payload"
    private const val VERSION = "version"
    private const val SCHEMA_FINGERPRINT = "schemaFingerprint"
    private const val STATE = "state"

    /** Writes the value of [name]: [text] as it is when there is one and [gen] writes JSON text, else [tree]. */
    private fun writeJson(
        gen: JsonGenerator,
        ctxt: SerializationContext,
        name: String,
        text: String?,
        tree: () -> JsonNode,
    ) {
        gen.writeName(name)
        if (text != null && gen is JsonGeneratorBase) gen.writeRawValue(text) else ctxt.writeValue(gen, tree())
    }

    /**
     * The JSON object [p] is at, read as a [target]; a value that is not one fails as Jackson fails an input that does not
     * match its type ([tools.jackson.databind.exc.MismatchedInputException]).
     */
    private fun objectOf(p: JsonParser, ctxt: DeserializationContext, target: Class<*>): JsonNode {
        val node = ctxt.readTree(p)
        return if (node.isObject) node else ctxt.reportInputMismatch(target, "A ${target.simpleName} is a JSON object.")
    }

    /** [node]'s [field]; one absent or null fails as a missing creator property would. */
    private fun required(ctxt: DeserializationContext, node: JsonNode, field: String, target: Class<*>): JsonNode =
        node.get(field)?.takeUnless { it.isNull }
            ?: ctxt.reportInputMismatch(target, "A ${target.simpleName} names its $field.")

    private object PayloadWriter : ValueSerializer<StateEventPayload>() {
        override fun serialize(value: StateEventPayload, gen: JsonGenerator, ctxt: SerializationContext) {
            gen.writeStartObject(value)
            gen.writeStringProperty(NAME, value.name)
            gen.writeNumberProperty(REVISION, value.revision)
            writeJson(gen, ctxt, PAYLOAD, value.payloadText) { value.payload }
            gen.writeEndObject()
        }
    }

    private object PayloadReader : ValueDeserializer<StateEventPayload>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): StateEventPayload {
            val type = StateEventPayload::class.java
            val node = objectOf(p, ctxt, type)
            return StateEventPayload(
                required(ctxt, node, NAME, type).asString(),
                required(ctxt, node, REVISION, type).asInt(),
                required(ctxt, node, PAYLOAD, type),
            )
        }
    }

    private object StateWriter : ValueSerializer<PublishedState>() {
        override fun serialize(value: PublishedState, gen: JsonGenerator, ctxt: SerializationContext) {
            gen.writeStartObject(value)
            gen.writeNumberProperty(VERSION, value.version)
            gen.writeStringProperty(SCHEMA_FINGERPRINT, value.schemaFingerprint)
            writeJson(gen, ctxt, STATE, value.stateText) { value.state }
            gen.writeEndObject()
        }
    }

    private object StateReader : ValueDeserializer<PublishedState>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): PublishedState {
            val type = PublishedState::class.java
            val node = objectOf(p, ctxt, type)
            return PublishedState(
                required(ctxt, node, VERSION, type).asInt(),
                required(ctxt, node, SCHEMA_FINGERPRINT, type).asString(),
                required(ctxt, node, STATE, type),
            )
        }
    }
}
