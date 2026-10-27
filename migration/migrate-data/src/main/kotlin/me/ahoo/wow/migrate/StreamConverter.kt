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
 *   records the scope after the commit: by a lifecycle transfer, or by a 9.x event whose mapping names its
 *   [owner][EventMapping.owner] field.
 * - **Operator.** The 9.x `command_operator` header ([NineXOperators]).
 * - **Causation.** The correlation id is the 9.x `trace_id` (the chain's root command), the command id when it has
 *   none. A root command's stream is caused by its command, as V10 records it; a derived command's stream records only
 *   the chain's root in 9.x, so its causation id is the root too.
 * - **Command type.** The mapped V10 name of the 9.x `upstream_name`.
 * - **Headers.** Only those the map lists, renamed; framework headers and wait plans are dropped.
 * - **Events.** Each event by its mapping: renamed to its full V10 name at the mapped revision, its top-level fields
 *   renamed as listed, its payload otherwise as stored (numbers exact); a retired event is written as stored under
 *   its retired name, which the V10 aggregate skips when it loads; a dropped event is left out.
 *
 * Every failure is a [MigrationException] naming the stream: the mapping check passed, so one means the store changed
 * since, or a stream is malformed.
 */
internal class StreamConverter(private val plan: MigrationPlan) {
    fun convert(stream: NineXStream): EventStreamRecord {
        val aggregate = plan.of(stream.aggregate)
        val subject = "9.x stream ${stream.id} (${stream.aggregate}/${stream.aggregateId} v${stream.version})"
        val commandType = aggregate.commandTypeOf(stream.commandName)
            ?: throw MigrationException("$subject: the 9.x command ${stream.commandName} has no mapping.")
        val events = eventsOf(aggregate, stream, subject)
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
            scope = aggregate.ownership.scopeOf(scopeOf(aggregate, stream, events)),
            causation = Causation(correlationId, correlationId),
            headers = aggregate.headers.mapNotNull { (from, to) -> stream.header[from]?.let { to to it } }.toMap(),
            operator = NineXOperators.operatorOf(stream.header, tenantId),
        )
    }

    private fun eventsOf(aggregate: AggregatePlan, stream: NineXStream, subject: String): List<EventRecord> =
        stream.events.mapNotNull { event ->
            val mapping = aggregate.eventOf(event.name, event.revision)
                ?: throw MigrationException("$subject: the 9.x event ${event.name}@${event.revision} has no mapping.")
            mapping.to?.let { to ->
                EventRecord(aggregate.fullName(to), mapping.toRevision, payload(event.body, mapping))
            }
        }

    private fun payload(body: JsonNode, mapping: EventMapping): String {
        if (mapping.fields.isEmpty() || body !is ObjectNode) {
            return WowJson.mapper.writeValueAsString(body)
        }
        val renamed = WowJson.mapper.createObjectNode()
        body.properties().forEach { (name, value) -> renamed.set(mapping.fields[name] ?: name, value) }
        return WowJson.mapper.writeValueAsString(renamed)
    }

    /**
     * The 9.x scope of [stream], moved by an owner or space transfer among its converted [events], or by a 9.x event
     * whose mapping names its owner field.
     */
    private fun scopeOf(aggregate: AggregatePlan, stream: NineXStream, events: List<EventRecord>): Scope {
        var ownerId = stream.ownerId.ifBlank { null }
        var spaceId = stream.spaceId.ifBlank { null }
        events.forEach { event ->
            when (event.name) {
                Lifecycle.OWNER_TRANSFERRED -> ownerId = field(event, "ownerId") ?: ownerId
                Lifecycle.SPACE_TRANSFERRED -> spaceId = field(event, "spaceId") ?: spaceId
            }
        }
        stream.events.forEach { event ->
            val field = aggregate.eventOf(event.name, event.revision)?.owner ?: return@forEach
            ownerId = event.body.get(field)?.takeIf { it.isString }?.stringValue()?.ifBlank { null } ?: ownerId
        }
        return Scope(ownerId, spaceId)
    }

    private fun field(event: EventRecord, name: String): String? =
        WowJson.mapper.readTree(event.payload).get(name)?.takeIf { it.isString }?.stringValue()?.ifBlank { null }
}
