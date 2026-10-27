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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.spi.json.query.EventFields
import me.ahoo.wow.spi.json.query.EventStreamFields
import java.util.concurrent.ConcurrentHashMap

/**
 * The query schema of each aggregate's `events` model as the Manifest states it (decision D235): the source of the
 * `events` array's declaration and the [EventsQueryModelProfile] of each aggregate type.
 *
 * `events[]` is a union discriminated by `name`: one variant per event of the aggregate (its
 * [variant][QueryFieldDeclaration.variant] the event's name, `<context>.<aggregate>.<Name>`), `{name, revision, body}`
 * with `name` that one name and `body` the event's schema in `$defs`, its `x-wow-*` keywords included, plus the system
 * events `wow.ScheduleSet` and `wow.ScheduleCancelled` (decision D198), whose bodies are objects of no declared property,
 * as is the body of an event whose schema `$defs` lacks. An aggregate the Manifest does not hold has one variant of any
 * name and a body of unknown shape. The stream's system fields come from the
 * profile.
 *
 * **Revision-stable body fields.** A pushed-down condition reads the body as the log stores it, at the revision it was
 * committed at, while a record shows it upgraded to the current revision (finding A4-16). A body path is stable when
 * every event that declares it is at revision 1: no upgrader ever ran, so the stored body is the body read. A body path
 * an event past revision 1 declares keeps only `PRESENCE` ([adapter]): filters, sorts and aggregations on it are
 * rejected (`UNSUPPORTED_CAPABILITY`), as are those on the undescribed bodies.
 *
 * Custom mask strategies (`x-wow-mask.strategy`) are resolved through [strategies].
 */
@InternalWowApi
class EventsQuerySchemas(
    private val manifest: Manifest,
    private val strategies: MaskStrategyResolver = MaskStrategyResolver.NONE,
) : QuerySchemaSource {
    override val priority: Int = QuerySchemaSourcePriority.MANIFEST

    private val declarations = ConcurrentHashMap<AggregateType, List<QuerySchemaDeclaration>>()
    private val profiles = ConcurrentHashMap<AggregateType, EventsQueryModelProfile>()

    override suspend fun load(context: QuerySchemaContext): List<QuerySchemaDeclaration> {
        if (context.model != QueryModel.EVENTS) return emptyList()
        return declarations.computeIfAbsent(context.aggregateType, ::declare)
    }

    /** The profile of [type]'s events: its events' stored names, its retired names and its events' revisions. */
    fun profile(type: AggregateType): EventsQueryModelProfile = profiles.computeIfAbsent(type) {
        val aggregate = manifest.aggregate(type) ?: return@computeIfAbsent EventsQueryModelProfile.DEFAULT
        EventsQueryModelProfile(
            storedNames = aggregate.events.associate { event ->
                val name = type.eventName(event.name)
                name to (setOf(name) + event.aliases.flatMap { listOf(it, type.eventName(it)) })
            },
            retired = aggregate.retired.flatMapTo(linkedSetOf()) { listOf(it, type.eventName(it)) },
            revisions = aggregate.events.associate { type.eventName(it.name) to it.revision },
        )
    }

    /**
     * [storage] of [type]'s events with the revision rule (see the class): a body path an upgraded event declares
     * keeps only `PRESENCE`.
     */
    fun adapter(type: AggregateType, storage: QueryStorageAdapter): QueryStorageAdapter =
        RevisionStableAdapter(storage, profile(type))

    /** The declaration of [type]'s `events`, compiled once. */
    fun declare(type: AggregateType): List<QuerySchemaDeclaration> {
        val aggregate = manifest.aggregate(type)
        val reader = ManifestSchemaReader(manifest.defs, type.context, strategies)
        val variants = aggregate?.events.orEmpty().map { event ->
            val schema = event.schema.takeIf { Manifest.schemaKey(it) in manifest.defs }?.let { mapOf(REF to it) }
            type.eventName(event.name) to variant(type.eventName(event.name), schema ?: UNDESCRIBED)
        } + SYSTEM_EVENTS.map { it to variant(it, UNDESCRIBED) }
        val items = if (aggregate == null) {
            variant(null, null)
        } else {
            mapOf("oneOf" to variants.map { it.second })
        }
        val events = reader.declaration(
            mapOf("type" to "array", "items" to items),
            QueryField(EventStreamFields.EVENTS)
        )
        val names = variants.map { it.first }
        val structure = events.copy(
            nullable = DeclarationValue.Unset,
            required = DeclarationValue.Unset,
            items = DeclarationValue.Set(events.items.valueOr(null)?.withVariants(names)),
        )
        return listOf(QuerySchemaDeclaration(mapOf(QueryField(EventStreamFields.EVENTS) to structure)))
    }

    /** Each alternative of this union marked as the variant of its name, in [names]' order. */
    private fun QueryFieldDeclaration.withVariants(names: List<String>): QueryFieldDeclaration {
        val branches = alternatives.valueOr(emptyList())
        if (branches.isEmpty()) {
            return if (names.size == 1) {
                copy(
                    variant = DeclarationValue.Set(names.single())
                )
            } else {
                this
            }
        }
        return copy(
            alternatives = DeclarationValue.Set(
                branches.mapIndexed { index, branch ->
                    names.getOrNull(index)?.let { branch.copy(variant = DeclarationValue.Set(it)) } ?: branch
                },
            ),
        )
    }

    private fun Manifest.aggregate(type: AggregateType): AggregateManifest? =
        contexts.firstOrNull { it.name == type.context }?.aggregates?.firstOrNull { it.name == type.name }

    /** Strips every capability but `PRESENCE` from the body paths of the upgraded events (see the class). */
    private class RevisionStableAdapter(
        private val storage: QueryStorageAdapter,
        private val profile: EventsQueryModelProfile,
    ) : QueryStorageAdapter {
        override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts =
            stable(storage.facts(logicalSchema), logicalSchema)

        override suspend fun refresh(logicalSchema: LogicalQuerySchema): QueryStorageFacts =
            stable(storage.refresh(logicalSchema), logicalSchema)

        private fun stable(facts: QueryStorageFacts, logicalSchema: LogicalQuerySchema): QueryStorageFacts {
            val unstable = unstableBodyPaths(logicalSchema)
            return QueryStorageFacts(
                bindings = facts.bindings.mapValues { (path, bindings) ->
                    if (!path.isBodyPath() || path.bodyNames() !in unstable) {
                        bindings
                    } else {
                        QueryValueBindings(
                            bindings.bindings.filterKeys { it == QueryCapability.PRESENCE },
                            bindings.projectionPath,
                            bindings.responsePath,
                        )
                    }
                },
                capabilities = facts.capabilities,
                fullProjectionAvailable = facts.fullProjectionAvailable,
                approximateMetrics = facts.approximateMetrics,
                storage = facts.storage,
                provisional = facts.provisional,
            )
        }

        /**
         * The body paths, as their property names below `body`, of every variant whose event is past revision 1, or
         * whose body is not described (a system event's, or any event's of an aggregate the Manifest does not hold).
         */
        private fun unstableBodyPaths(logicalSchema: LogicalQuerySchema): Set<List<String>> {
            val items = logicalSchema.root.properties[EventStreamFields.EVENTS]?.items ?: return emptySet()
            val variants = items.alternativesOrSelf().filter { it.kind == QueryValueKind.OBJECT }
            return buildSet {
                variants.filter { variant ->
                    val revision = variant.variant?.let(profile.revisions::get)
                    revision == null || revision > 1
                }.forEach { variant ->
                    variant.properties[EventFields.BODY]?.let { body -> collect(body, emptyList(), this) }
                }
            }
        }

        private fun collect(value: QueryValueSchema, names: List<String>, into: MutableSet<List<String>>) {
            into += names
            value.properties.forEach { (name, child) -> collect(child, names + name, into) }
            value.items?.let { collect(it, names, into) }
            value.additionalProperties?.let { collect(it, names, into) }
            value.alternatives.forEach { collect(it, names, into) }
        }

        private fun QueryPathTemplate.isBodyPath(): Boolean = segments.size >= BODY_DEPTH &&
            segments[0] == QueryPathSegment.Property(EventStreamFields.EVENTS) &&
            segments[1] == QueryPathSegment.Item &&
            segments[2] == QueryPathSegment.Property(EventFields.BODY)

        private fun QueryPathTemplate.bodyNames(): List<String> =
            segments.drop(BODY_DEPTH).filterIsInstance<QueryPathSegment.Property>().map { it.name }
    }

    private companion object {
        const val REF = "\$ref"
        const val BODY_DEPTH = 3

        /**
         * The body of an event whose schema the Manifest does not carry: an object of no declared property, so a body
         * path of the other events stays theirs alone (an unknown value would take every capability from it).
         */
        val UNDESCRIBED: Map<String, Any?> = mapOf("type" to "object")

        /** The system events a commit may hold besides the aggregate's own (decision D198). */
        val SYSTEM_EVENTS = listOf(Schedules.SCHEDULE_SET, Schedules.SCHEDULE_CANCELLED)

        /** The log's name of [name], an event of this aggregate type: `<context>.<aggregate>.<Name>` (D55). */
        fun AggregateType.eventName(name: String): String = if ('.' in name) name else "$context.${this.name}.$name"

        /** One variant of `events[]`: [name] (any, when null) and the body [schema] (undescribed, when null). */
        fun variant(name: String?, schema: Map<String, Any?>?): Map<String, Any?> = mapOf(
            "type" to "object",
            "required" to listOf(EventFields.NAME, EventFields.REVISION, EventFields.BODY),
            "properties" to linkedMapOf(
                EventFields.NAME to (
                    mapOf("type" to "string") + (name?.let { mapOf("enum" to listOf(it)) } ?: emptyMap())
                    ),
                EventFields.REVISION to mapOf("type" to "integer"),
                EventFields.BODY to (schema ?: emptyMap()),
            ),
        )
    }
}
