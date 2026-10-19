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
import java.util.Collections

/**
 * The names of an event stream record's fields (decision D235): one record per stream, the commit of one command at
 * one version, as `me.ahoo.wow.spi.log.EventStreamRecord` holds it. [OPERATOR] is `{id, tenantId, crossScope}`, or
 * null for an anonymous caller (decision D163); [EVENTS] holds each event as `{name, revision, body}` ([EventFields]).
 */
object EventStreamFields {
    const val AGGREGATE_ID: String = "aggregateId"
    const val TENANT_ID: String = "tenantId"
    const val VERSION: String = "version"
    const val COMMIT_TIME: String = "commitTime"
    const val COMMAND_ID: String = "commandId"
    const val REQUEST_ID: String = "requestId"
    const val COMMAND_TYPE: String = "commandType"
    const val OWNER_ID: String = "ownerId"
    const val SPACE_ID: String = "spaceId"
    const val OPERATOR: String = "operator"
    const val EVENTS: String = "events"
}

/** The properties of one event of [EventStreamFields.EVENTS]. */
object EventFields {
    /** The event's name, `<context>.<aggregate>.<Name>` (decision D55), or a system event's (`wow.ScheduleSet`). */
    const val NAME: String = "name"

    /** The revision of the event's schema. */
    const val REVISION: String = "revision"

    /** The event's payload. */
    const val BODY: String = "body"
}

/**
 * The event streams of one aggregate type (decision D235): one record per stream, identified by `(aggregateId,
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
 * - [retired]: the stored names the aggregate retired (decision D219), which every element scope of `events` excludes,
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
                    // Whose name the commit is recorded in; null when that caller was anonymous (D163).
                    EventStreamFields.OPERATOR.operatorField(),
                    EventStreamFields.EVENTS.payloadField(),
                ),
            ),
        )
    }
}
