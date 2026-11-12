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

package me.ahoo.wow.migrate

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * Converts a 9.x stream into the V10 stream the import appends (step 2):
 *
 * - **Identity.** The ref is the mapped aggregate type with the 9.x tenant and aggregate id; the version, command id,
 *   request id (the command id when 9.x recorded none) and commit time (the 9.x `createTime`) are kept. The 9.x stream
 *   id has no V10 field.
 * - **Scope.** The 9.x owner and space, blank read as none, narrowed by the V10 aggregate's ownership
 *   (a `TENANT` aggregate records no owner). A stream that transfers the owner or the space carries the new one, as V10
 *   records the scope after the commit: by the lifecycle transfers it writes, mapped or composed
 *   ([EventMapping.lifecycle]), so a state rebuilt from the events has the scope the stream records.
 * - **Operator.** The 9.x `command_operator` header ([NineXOperators]).
 * - **Causation.** The correlation id is the 9.x `trace_id` (the chain's root command), the command id when it has
 *   none. A root command's stream is caused by its command, as V10 records it; a derived command's stream records only
 *   the chain's root in 9.x, so its causation id is the root too.
 * - **Command type.** The mapped V10 name of the 9.x `upstream_name`.
 * - **Headers.** Only those the map lists, renamed; framework headers and wait plans are dropped. The stream's commit
 *   time is the 9.x `createTime`, when it occurred, and the time it is written is the import's ([clock]), kept as
 *   `wow.commit.written-at` ([EventStreamRecord.WRITTEN_AT]) when the two differ, as V10 keeps it for any commit whose
 *   time is earlier than its writing: a timer the stream sets, a subscription's start at an instant and the lag
 *   count from when it was written.
 * - **Events.** Each event by its mapping: renamed to its full V10 name at the mapped revision, its top-level fields
 *   renamed as listed, its payload otherwise as stored (numbers exact); a retired event is written as stored under
 *   its retired name, which the V10 aggregate skips when it loads; a dropped event is left out. A mapping's
 *   [lifecycle][EventMapping.lifecycle] event follows its event, its payload the listed 9.x fields renamed; a 9.x
 *   payload without one fails the stream, naming the event's position.
 *
 * Every failure is a [MigrationException] naming the stream: the mapping check passed, so one means the store changed
 * since, or a stream is malformed. A stream of an aggregate id V10 refuses ([RefusedIds]) fails too, naming the entry
 * that excludes it.
 */
internal class StreamConverter(
    private val plan: MigrationPlan,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The headers the map lists, renamed, and when the stream is written ([clock]) when that is after it occurred. */
    private fun headersOf(aggregate: AggregatePlan, stream: NineXStream): Map<String, String> {
        val mapped = aggregate.headers.mapNotNull { (from, to) -> stream.header[from]?.let { to to it } }.toMap()
        val writtenAt = clock()
        return if (writtenAt == stream.createTime) mapped else mapped + (EventStreamRecord.WRITTEN_AT to "$writtenAt")
    }

    fun convert(stream: NineXStream): EventStreamRecord {
        val aggregate = plan.of(stream.aggregate)
        val subject = subjectOf(stream)
        refuseId(stream, subject)
        val commandType = aggregate.commandTypeOf(stream.commandName)
            ?: throw MigrationException("$subject: the 9.x command ${stream.commandName} has no mapping.")
        val events = eventsOf(aggregate, stream, subject).flatMap { it.second }
        if (events.isEmpty()) {
            // The mapping check counts such streams and fails before the import.
            throw MigrationException(
                "$subject: every event of the stream is dropped, so its version would be empty; map them with " +
                    "retire to a name in the aggregate's retiredEvents instead.",
            )
        }
        val tenantId = stream.aggregateId.tenantId
        val correlationId = stream.header[NineXHeaders.TRACE_ID]?.takeIf { it.isNotBlank() } ?: stream.commandId
        return EventStreamRecord(
            ref = aggregate.target.ref(tenantId, stream.aggregateId.id),
            version = stream.version,
            commandId = stream.commandId,
            requestId = stream.requestId.ifBlank { stream.commandId },
            commandType = commandType,
            commitTime = stream.createTime,
            events = events,
            scope = aggregate.ownership.scopeOf(scopeOf(stream, events)),
            causation = Causation(correlationId, correlationId),
            headers = headersOf(aggregate, stream),
            operator = NineXOperators.operatorOf(stream.header, tenantId),
        )
    }

    /**
     * Fails [stream], which [subject] names, when V10 refuses its aggregate id or its blank tenant, naming the entry
     * that excludes it.
     */
    private fun refuseId(stream: NineXStream, subject: String) {
        val reason = RefusedIds.reasonOf(stream.aggregateId.id)?.let { "its aggregate id is $it" }
            ?: "it records no tenant".takeIf { stream.aggregateId.tenantId.isBlank() }
            ?: return
        val name = RefusedIds.quoted(RefusedIds.nameOf(stream.aggregateId.id))
        throw MigrationException(
            "$subject: $reason, which V10 refuses; exclude it with {\"aggregate\": " +
                "\"${stream.aggregate}\", \"stream\": $name, \"reason\": \"…\"}.",
        )
    }

    /**
     * The events of [stream], each with the V10 records [convert] writes of it: none for an event the map drops, and
     * the mapped event then its lifecycle event for one composed with it. A stream that does not convert fails as
     * [convert] fails it.
     */
    fun eventsOf(stream: NineXStream): List<Pair<NineXEvent, List<EventRecord>>> =
        eventsOf(plan.of(stream.aggregate), stream, subjectOf(stream))

    /** [stream] as a message names it. */
    fun subjectOf(stream: NineXStream): String =
        "9.x stream ${stream.id} (${stream.aggregate}/${stream.aggregateId} v${stream.version})"

    private fun eventsOf(
        aggregate: AggregatePlan,
        stream: NineXStream,
        subject: String,
    ): List<Pair<NineXEvent, List<EventRecord>>> =
        stream.events.mapIndexed { position, event ->
            val mapping = aggregate.eventOf(event.name, event.revision)
                ?: throw MigrationException("$subject: the 9.x event ${event.name}@${event.revision} has no mapping.")
            val to = mapping.to ?: return@mapIndexed event to emptyList()
            val payload = if (mapping.tags) {
                tagged(event, mapping, aggregate, "$subject, event $position")
            } else {
                payload(event.body, mapping)
            }
            event to listOfNotNull(
                EventRecord(aggregate.fullName(to), mapping.toRevision, payload),
                mapping.lifecycle?.let { lifecycle -> lifecycleOf(lifecycle, event, "$subject, event $position") },
            )
        }

    /** The [lifecycle] event composed with [event]: its payload the listed fields of [event]'s, renamed. */
    private fun lifecycleOf(lifecycle: LifecycleMapping, event: NineXEvent, at: String): EventRecord {
        val payload = WowJson.mapper.createObjectNode()
        lifecycle.fields.forEach { (from, to) ->
            val value = event.body.get(from) ?: throw MigrationException(
                "$at (${event.name}): its payload has no $from, which the lifecycle event ${lifecycle.to} takes as $to.",
            )
            payload.set(to, value)
        }
        return EventRecord(lifecycle.to, 1, WowJson.mapper.writeValueAsString(payload))
    }

    /**
     * The payload of 9.x's tags [event] ([EventMapping.tags]): its fields but `tags`, renamed, and one field per applied
     * tag key of the aggregate, named as its access attribute, holding the key's values as the attribute does
     * ([TagValues]), none when the event holds no such key: 9.x replaced the whole map.
     */
    private fun tagged(event: NineXEvent, mapping: EventMapping, aggregate: AggregatePlan, at: String): String {
        val payload = WowJson.mapper.createObjectNode()
        event.body.properties().forEach { (name, value) ->
            if (name != TAGS) {
                payload.set(mapping.fields[name] ?: name, value)
            }
        }
        val tags = NineXTags.of(event.body.get(TAGS))
        aggregate.mapping.tags.filter { it.written }.forEach { tag ->
            val name = tag.to!!
            val attribute = aggregate.accessOf(name) ?: throw MigrationException(
                "$at (${event.name}): the 9.x tag ${tag.key} maps to $name, which ${aggregate.target} does not declare " +
                    "as an access attribute.",
            )
            val value = try {
                TagValues.nodeOf(attribute, tags?.get(tag.key))
            } catch (failure: TagValueException) {
                throw MigrationException("$at (${event.name}): the 9.x tag ${tag.key} ${failure.message}.")
            }
            payload.set(name, value)
        }
        return WowJson.mapper.writeValueAsString(payload)
    }

    private fun payload(body: JsonNode, mapping: EventMapping): String {
        if (mapping.fields.isEmpty() || body !is ObjectNode) {
            return WowJson.mapper.writeValueAsString(body)
        }
        val renamed = WowJson.mapper.createObjectNode()
        body.properties().forEach { (name, value) -> renamed.set(mapping.fields[name] ?: name, value) }
        return WowJson.mapper.writeValueAsString(renamed)
    }

    /** The 9.x scope of [stream], moved by an owner or space transfer among its converted [events]. */
    private fun scopeOf(stream: NineXStream, events: List<EventRecord>): Scope {
        var ownerId = stream.ownerId.ifBlank { null }
        var spaceId = stream.spaceId.ifBlank { null }
        events.forEach { event ->
            when (event.name) {
                Lifecycle.OWNER_TRANSFERRED -> ownerId = field(event, "ownerId") ?: ownerId
                Lifecycle.SPACE_TRANSFERRED -> spaceId = field(event, "spaceId") ?: spaceId
            }
        }
        return Scope(ownerId, spaceId)
    }

    private fun field(event: EventRecord, name: String): String? =
        WowJson.mapper.readTree(event.payload).get(name)?.takeIf { it.isString }?.stringValue()?.ifBlank { null }
}

/** The field of 9.x's tags event that holds the tags: `ResourceTagsApplied.tags`. */
internal const val TAGS = "tags"
