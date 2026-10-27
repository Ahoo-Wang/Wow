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

package me.ahoo.wow.query.mask

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.schema.ManifestSchemaReader
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaMerger
import me.ahoo.wow.spi.json.query.SnapshotFields
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The response masks of the payloads a state read and the `events` model return outside a snapshot query:
 * an aggregate's [state] and an event's [body], masked by the `x-wow-sensitive` and `x-wow-mask`
 * keywords of their schemas in [manifest]'s `$defs`, as a snapshot query masks its records ([SchemaMasker]): every
 * sensitive value, whatever its level, is replaced by its mask for every caller. A payload whose schema the Manifest
 * does not carry, or that declares nothing sensitive, is returned as it is.
 *
 * Each schema is compiled once, on first use; [strategies] resolves the custom mask strategies, and one it does not
 * know fails the read rather than return the raw value.
 */
@InternalWowApi
class PayloadMasks(
    private val manifest: Manifest,
    private val strategies: MaskStrategyResolver = MaskStrategyResolver.NONE,
) {
    private val maskers = ConcurrentHashMap<MaskKey, Optional<SchemaMasker>>()

    /** [state], a state of [type], masked in place where it can be; the masked value. */
    fun state(type: AggregateType, state: JsonNode): JsonNode {
        val aggregate = aggregate(type) ?: return state
        return mask(MaskKey(type.context, aggregate.state.schema), state)
    }

    /**
     * [body], the body of [type]'s event named [name], masked in place where it can be. [name] is the event's name in
     * the log (`<context>.<aggregate>.<Name>`) or the Manifest's, or one of its aliases.
     */
    fun event(type: AggregateType, name: String, body: JsonNode): JsonNode {
        val local = name.removePrefix("${type.context}.${type.name}.")
        val event = aggregate(type)?.events?.firstOrNull { event ->
            event.name == local || name in event.aliases || local in event.aliases
        } ?: return body
        return mask(MaskKey(type.context, event.schema), body)
    }

    private fun aggregate(type: AggregateType): AggregateManifest? =
        manifest.contexts.firstOrNull { it.name == type.context }?.aggregates?.firstOrNull { it.name == type.name }

    private fun mask(key: MaskKey, payload: JsonNode): JsonNode {
        val masker = maskers.computeIfAbsent(key) { Optional.ofNullable(compile(it)) }.orElse(null) ?: return payload
        val record = JsonNodeFactory.instance.objectNode().set(PAYLOAD.path, payload)
        return masker.mask(record).get(PAYLOAD.path)
    }

    /**
     * The masker of [key]'s schema declared as a snapshot's `state` is ([me.ahoo.wow.query.schema.ManifestQuerySchemaSource]),
     * whatever the payload: the snapshot model is the one whose payload masks are compiled; null when it masks nothing.
     */
    private fun compile(key: MaskKey): SchemaMasker? {
        if (!key.schema.startsWith(DEFS_PREFIX) || Manifest.schemaKey(key.schema) !in manifest.defs) return null
        val declaration = ManifestSchemaReader(manifest.defs, key.context, strategies)
            .declaration(mapOf(REF to key.schema), PAYLOAD)
        val logical = QuerySchemaMerger().merge(
            QuerySchemaDeclaration(mapOf(PAYLOAD to declaration)),
            emptyList(),
            extensionRoot = null,
        )
        return SchemaMasker.create(QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), logical, emptyMap()))
    }

    private data class MaskKey(val context: String, val schema: String)

    @InternalWowApi
    companion object {
        /** Masks nothing. */
        @JvmField
        val NONE: PayloadMasks = PayloadMasks(Manifest())

        private const val REF = "\$ref"
        private const val DEFS_PREFIX = "#/\$defs/"
        private val PAYLOAD = QueryField(SnapshotFields.STATE)
    }
}
