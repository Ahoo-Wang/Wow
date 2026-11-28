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
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.Schedules
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
import me.ahoo.wow.spi.read.StateMasks
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The response masks of the payloads a state read and the `events` model return outside a snapshot query:
 * an aggregate's [state] and an event's [body], masked by the `x-wow-sensitive` and `x-wow-mask`
 * keywords of their schemas in [manifest]'s `$defs`, as a snapshot query masks its records ([SchemaMasker]): every
 * sensitive value, whatever its level, is replaced by its mask for every caller. A payload that declares nothing
 * sensitive is returned as it is.
 *
 * **What the node finds no schema for is masked whole**: an event the Manifest does not know, by
 * its name or by a revision newer than the Manifest's ([EventManifest.revision][me.ahoo.wow.manifest.EventManifest]),
 * such as one a newer node wrote during a rolling upgrade, is answered with the body JSON `null`, never as it is: the
 * node cannot tell what in it is sensitive. An event the Manifest lists without carrying its schema declares nothing
 * sensitive, and is returned as it is. Wow's own events
 * (`wow.lifecycle.*`, `wow.schedule.*`) are known to every node, whatever the Manifest lists.
 *
 * Each schema is compiled once, on first use; [strategies] resolves the custom mask strategies, and one it does not
 * know fails the read rather than return the raw value. A read that reveals `DISPLAY` values
 * ([me.ahoo.wow.query.QueryCaller.revealsDisplay]) is masked by the schema's `CONFIDENTIAL` masks alone, compiled on
 * its first such read.
 *
 * They are the node's [StateMasks] too ([stateMaskOf], [eventMaskOf]): a writer that stores or publishes a state or an
 * event's body where a reader takes it as a read, the state view cache's sink or the transport sink, masks it by the
 * very function a read applies. An event is found by its name once, then by the name it was asked by.
 *
 * A schedule's event, `wow.schedule.ScheduleSet`, holds the body of the command it will send: that `command` is masked
 * as a body of its command type (`commandType`, `context.aggregate.Command`) is, by its schema in [manifest] or, for a
 * command of another context, in one of [peers], the contracts of the contexts the node sends commands to. A command
 * whose type none of them names, or that names no type, or that is not an object, is masked whole, `null`, as an event
 * the Manifest does not know is.
 *
 * [NONE] masks nothing, not even what it knows no schema for ([masksUnknown] false): it is for a read that is not
 * masked at all.
 */
@InternalWowApi
class PayloadMasks(
    private val manifest: Manifest,
    private val strategies: MaskStrategyResolver = MaskStrategyResolver.NONE,
    peers: List<Manifest> = emptyList(),
    private val masksUnknown: Boolean = true,
) : StateMasks {
    /** The node's Manifest, then its peers': where a scheduled command's schema is found, by [MaskKey.source]. */
    private val manifests: List<Manifest> = listOf(manifest) + peers

    private val maskers = ConcurrentHashMap<MaskKey, Optional<SchemaMasker>>()

    /** The schema of each scheduled command type, by its name; empty when no Manifest names it. */
    private val commandSchemas = ConcurrentHashMap<String, Optional<CommandSchema>>()

    /** The schema of each event by the type and the name it was asked by; empty when the Manifest has none. */
    private val eventSchemas = ConcurrentHashMap<EventKey, Optional<EventSchema>>()

    /**
     * [state], a state of [type], masked in place where it can be; the masked value. With [revealsDisplay], its
     * `DISPLAY` values are left raw.
     */
    @JvmOverloads
    fun state(type: AggregateType, state: JsonNode, revealsDisplay: Boolean = false): JsonNode {
        val aggregate = aggregate(type) ?: return state
        return mask(MaskKey(type.context, aggregate.state.schema, revealsDisplay), state)
    }

    override fun stateMaskOf(type: AggregateType, revealsDisplay: Boolean): ((JsonNode) -> JsonNode)? {
        val aggregate = aggregate(type) ?: return null
        return maskOf(MaskKey(type.context, aggregate.state.schema, revealsDisplay))
    }

    /**
     * [body], the body of [type]'s event named [name] stored at [revision], masked in place where it can be; `null`
     * when the Manifest does not know the event at that revision. [name] is the event's name in the log
     * (`<context>.<aggregate>.<Name>`) or the Manifest's, or one of its aliases. With [revealsDisplay], its `DISPLAY`
     * values are left raw.
     */
    @JvmOverloads
    fun event(
        type: AggregateType,
        name: String,
        revision: Int,
        body: JsonNode,
        revealsDisplay: Boolean = false,
    ): JsonNode {
        if (name == Schedules.SCHEDULE_SET) {
            return scheduled(body, revealsDisplay)
        }
        if (name in FRAMEWORK_EVENTS) {
            return body
        }
        val schema = eventSchema(type, name)?.takeIf { revision <= it.revision } ?: return unknown(body)
        return mask(MaskKey(type.context, schema.ref, revealsDisplay), body)
    }

    /**
     * The mask of the body of [type]'s event named [event], at its current revision, as [event] masks it: one that
     * masks the body whole when the Manifest does not know the event; null when its schema masks nothing.
     */
    override fun eventMaskOf(type: AggregateType, event: String, revealsDisplay: Boolean): ((JsonNode) -> JsonNode)? {
        if (event == Schedules.SCHEDULE_SET) {
            return { body -> scheduled(body, revealsDisplay) }
        }
        if (event in FRAMEWORK_EVENTS) {
            return null
        }
        val schema = eventSchema(type, event) ?: return if (masksUnknown) WHOLE else null
        return maskOf(MaskKey(type.context, schema.ref, revealsDisplay))
    }

    /** [body], a schedule's event, with the command it holds masked as a body of its command type; masked in place. */
    private fun scheduled(body: JsonNode, revealsDisplay: Boolean): JsonNode {
        val event = body as? ObjectNode ?: return unknown(body)
        val command = event.get(COMMAND) ?: return event
        val schema = event.get(COMMAND_TYPE)?.takeIf { it.isString }?.stringValue()?.let(::commandSchema)
        if (schema == null || !command.isObject) {
            event.set(COMMAND, unknown(command))
            return event
        }
        event.set(COMMAND, mask(MaskKey(schema.context, schema.ref, revealsDisplay, schema.source), command))
        return event
    }

    /** [payload] masked whole, as what the node finds no schema for is; as it is for [NONE]. */
    private fun unknown(payload: JsonNode): JsonNode = if (masksUnknown) WHOLE(payload) else payload

    /**
     * The schema of the command type named [name] (`context.aggregate.Command`, or a former name), in the first
     * Manifest that has its aggregate and names the command; null when none does.
     */
    private fun commandSchema(name: String): CommandSchema? = commandSchemas.computeIfAbsent(name) {
        Optional.ofNullable(
            manifests.withIndex().firstNotNullOfOrNull { (source, candidate) ->
                candidate.contexts.firstNotNullOfOrNull { context ->
                    context.aggregates.firstNotNullOfOrNull { aggregate ->
                        val local = name.removePrefix("${context.name}.${aggregate.name}.")
                        aggregate.commands.firstOrNull { command ->
                            local != name && (command.name == local || local in command.aliases) ||
                                name in command.aliases
                        }?.let { CommandSchema(context.name, it.schema, source) }
                    }
                }
            },
        )
    }.orElse(null)

    /** The mask of [key]'s schema, its masker compiled now: null when it masks nothing. */
    private fun maskOf(key: MaskKey): ((JsonNode) -> JsonNode)? {
        val masker = masker(key) ?: return null
        return { payload -> mask(masker, payload) }
    }

    private fun eventSchema(type: AggregateType, name: String): EventSchema? =
        eventSchemas.computeIfAbsent(EventKey(type, name)) { key ->
            val local = key.name.removePrefix("${type.context}.${type.name}.")
            val event = aggregate(type)?.events?.firstOrNull { event ->
                event.name == local || key.name in event.aliases || local in event.aliases
            }
            Optional.ofNullable(event?.let { EventSchema(it.schema, it.revision) })
        }.orElse(null)

    private fun aggregate(type: AggregateType): AggregateManifest? =
        manifest.contexts.firstOrNull { it.name == type.context }?.aggregates?.firstOrNull { it.name == type.name }

    private fun masker(key: MaskKey): SchemaMasker? =
        maskers.computeIfAbsent(key) { Optional.ofNullable(compile(it)) }.orElse(null)

    private fun mask(key: MaskKey, payload: JsonNode): JsonNode {
        val masker = masker(key) ?: return payload
        return mask(masker, payload)
    }

    private fun mask(masker: SchemaMasker, payload: JsonNode): JsonNode {
        val record = JsonNodeFactory.instance.objectNode().set(PAYLOAD.path, payload)
        return masker.mask(record).get(PAYLOAD.path)
    }

    /**
     * The masker of [key]'s schema declared as a snapshot's `state` is ([me.ahoo.wow.query.schema.ManifestQuerySchemaSource]),
     * whatever the payload: the snapshot model is the one whose payload masks are compiled; null when it masks nothing.
     */
    private fun compile(key: MaskKey): SchemaMasker? {
        val defs = manifests[key.source].defs
        if (!Manifest.isSchemaRef(key.schema) || Manifest.schemaKey(key.schema) !in defs) return null
        val declaration = ManifestSchemaReader(defs, key.context, strategies)
            .declaration(mapOf(REF to key.schema), PAYLOAD)
        val logical = QuerySchemaMerger().merge(
            QuerySchemaDeclaration(mapOf(PAYLOAD to declaration)),
            emptyList(),
            extensionRoots = null,
        )
        val schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), logical, emptyMap())
        return SchemaMasker.create(schema, key.revealsDisplay)
    }

    /** A schema to mask by: [schema] of [context], in the Manifest [source] of [manifests] (the node's own: 0). */
    private data class MaskKey(
        val context: String,
        val schema: String,
        val revealsDisplay: Boolean = false,
        val source: Int = 0,
    )

    /** A command type's schema [ref] in [context], declared by the Manifest [source] of [manifests]. */
    private data class CommandSchema(val context: String, val ref: String, val source: Int)

    private data class EventKey(val type: AggregateType, val name: String)

    /** An event's schema [ref] and the [revision] the Manifest knows it at. */
    private data class EventSchema(val ref: String, val revision: Int)

    @InternalWowApi
    companion object {
        /** Masks nothing, not even what it knows no schema for. */
        @JvmField
        val NONE: PayloadMasks = PayloadMasks(Manifest(), masksUnknown = false)

        /** The events of Wow's own aggregates and lifecycle, which every node knows, beside the schedule's. */
        private val FRAMEWORK_EVENTS: Set<String> =
            (Lifecycle.EVENTS.keys + Schedules.EVENTS.keys + ScheduleJobs.EVENTS.keys) - Schedules.SCHEDULE_SET

        /** What the node finds no schema for, masked whole: `null`, whatever it was. */
        private val WHOLE: (JsonNode) -> JsonNode = { JsonNodeFactory.instance.nullNode() }

        private const val REF = "\$ref"
        private const val COMMAND = "command"
        private const val COMMAND_TYPE = "commandType"
        private val PAYLOAD = QueryField(SnapshotFields.STATE)
    }
}
