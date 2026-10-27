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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.spi.json.query.EventFields
import me.ahoo.wow.spi.json.query.EventStreamFields
import java.util.Collections

/**
 * The event streams of one aggregate type: one record per stream, identified by `(aggregateId,
 * version)`, its events an array whose conditions are element predicates (`ELEMENT_MATCH`), so `count` counts commits
 * and a page never splits one. Per-event analytics expand the array (`AggregationQuery.elements`).
 *
 * - The identity a record-id filter (`ID`, `IDS`) targets is the aggregate id, as before the unified engine; the
 *   cursor's tie-breakers are `(tenantId, aggregateId, version)`, the stream's key in its log.
 * - The default `FIRST` / `LAST` order is the commit time. A stream is never deleted: the model has no `deleted` field
 *   and no default scope.
 * - A projection keeps or drops `events` as a whole: an event is read (upgraded, masked) as one value.
 *
 * What one aggregate type's Manifest says about its events reaches admission here:
 * - [storedNames]: the names each event is stored under besides its current one (its `@Alias`es, as stored), so a
 *   name condition inside an element scope of `events` matches the events the log stores under them;
 * - [retired]: the stored names the aggregate retired, which every element scope of `events` excludes,
 *   so a condition never matches an event the record does not show;
 * - [revisions]: each event's current revision; a body path of an event past revision 1 is not filtered, sorted or
 *   aggregated (see [EventsQuerySchemas]).
 */
@InternalWowApi
class EventsQueryModelProfile internal constructor(
    val storedNames: Map<String, Set<String>> = emptyMap(),
    val retired: Set<String> = emptySet(),
    val revisions: Map<String, Int> = emptyMap(),
) : BuiltInQueryModelProfile(QueryModel.EVENTS) {
    override val identityField: QueryField = QueryField(EventStreamFields.AGGREGATE_ID)
    override val payloadField: QueryField = QueryField(EventStreamFields.EVENTS)
    override val eventTimeField: QueryField = QueryField(EventStreamFields.COMMIT_TIME)

    override val cursorUniqueKey: List<QueryField> = listOf(
        QueryField(EventStreamFields.TENANT_ID),
        QueryField(EventStreamFields.AGGREGATE_ID),
        QueryField(EventStreamFields.VERSION),
    )

    override val systemDeclaration: QuerySchemaDeclaration
        get() = SYSTEM_DECLARATION

    /** The element array of the events, the container [storedNames] and [retired] apply to. */
    val eventsField: QueryField
        get() = payloadField

    /** The name field of an event, relative to [eventsField]. */
    val nameField: QueryField = QueryField(EventFields.NAME)

    /** The names [name] is stored under: itself and its aliases. */
    fun storedNamesOf(name: String): Set<String> = storedNames[name] ?: setOf(name)

    /**
     * The gateway masks each event's body by its own event's schema ([me.ahoo.wow.query.events.EventStreamRecords]):
     * the union of every event's body under one path cannot say which event's mask applies.
     */
    override val masksResponses: Boolean
        get() = false

    /** Rejects a projection below `events`: an event is read as a whole, its body upgraded to its current revision. */
    override fun validateProjection(projection: Projection) {
        (projection.include + projection.exclude).firstOrNull { it.relativeTo(payloadField) != null }?.let { field ->
            throw QueryViolation.StorageUnsupported("a projection inside [${payloadField.path}]", field).rejection()
        }
    }

    override fun equals(other: Any?): Boolean = other is EventsQueryModelProfile &&
        storedNames == other.storedNames && retired == other.retired && revisions == other.revisions

    override fun hashCode(): Int = listOf(storedNames, retired, revisions).hashCode()

    override fun toString(): String = "EventsQueryModelProfile(retired=$retired)"

    @InternalWowApi
    companion object {
        /** The profile of an aggregate whose events the Manifest does not describe. */
        @JvmField
        val DEFAULT: EventsQueryModelProfile = EventsQueryModelProfile()

        private val SYSTEM_DECLARATION = QuerySchemaDeclaration(
            Collections.unmodifiableMap(
                linkedMapOf(
                    EventStreamFields.AGGREGATE_ID.stringField(),
                    EventStreamFields.TENANT_ID.stringField(),
                    EventStreamFields.VERSION.integerField(),
                    EventStreamFields.COMMIT_TIME.epochField(),
                    EventStreamFields.COMMAND_ID.stringField(),
                    EventStreamFields.REQUEST_ID.stringField(),
                    EventStreamFields.COMMAND_TYPE.stringField(),
                    // A stream without an owner or a space holds null there (Scope.NONE).
                    EventStreamFields.OWNER_ID.stringField(nullable = true),
                    EventStreamFields.SPACE_ID.stringField(nullable = true),
                    // Whose name the commit is recorded in; null when that caller was anonymous.
                    EventStreamFields.OPERATOR.operatorField(),
                    // The tags after the commit; null when the log did not record them.
                    EventStreamFields.TAGS.tagsField(nullable = true),
                    EventStreamFields.EVENTS.payloadField(),
                ),
            ),
        )
    }
}
