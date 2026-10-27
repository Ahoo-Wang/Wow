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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * Converts a V10 stream back into the 9.x stream [StreamConverter] would have read it from, for the rollback export:
 * the map read backwards, for the event types 9.x already had.
 *
 * - **Aggregate.** The 9.x aggregate the map imports into the stream's V10 type; a type no 9.x aggregate maps to, or
 *   that two map to, has no way back.
 * - **Events.** Each event by the mapping whose V10 name and revision are the event's: renamed to the 9.x name at the
 *   9.x revision (the mapping's `fromRevision`, else the latest revision of that name the 9.x store holds, else 9.x's
 *   default), its renamed top-level fields given their 9.x names back. An event the map reaches at another revision
 *   (V10 moved its shape on with `@Upgrade`) or not at all is a V10-only type: 9.x cannot read it, and the stream has
 *   no way back.
 * - **Command.** The 9.x name of the stream's command type, by the map; a command the map does not name keeps its V10
 *   name in the `upstream_name` header, which 9.x only records.
 * - **Identity, scope and headers.** The version, command id, request id and commit time are kept; the owner and space
 *   go back to the stream's own fields (blank when none); the correlation id becomes `trace_id`, the command id
 *   `upstream_id`, the operator's id `command_operator`, and the headers the map keeps get their 9.x keys back.
 * - **Ids.** 9.x needs an id per stream and per event, which V10 does not record: the stream id is derived from the
 *   stream's ref and version, the same on every run, and its events are numbered after it.
 */
internal class StreamReverter(
    private val plan: MigrationPlan,
    private val revisionsOf: (NineXAggregate, String) -> List<String>
) {
    private val byTarget: Map<AggregateType, List<AggregatePlan>> = plan.aggregates.values.groupBy { it.target }
    private val headers: Map<String, String> = plan.map.headers.entries.associate { (from, to) -> to to from }

    /** The 9.x aggregate the V10 [type] goes back to; null when no 9.x aggregate maps to it, or several do. */
    fun aggregateOf(type: AggregateType): AggregatePlan? = byTarget[type]?.singleOrNull()

    /** Why [record] cannot go back to 9.x: each V10-only type it holds, by name; empty when it can. */
    fun problemsOf(record: EventStreamRecord): List<String> {
        val type = record.ref.type
        val candidates = byTarget[type].orEmpty()
        if (candidates.size != 1) {
            return listOf(
                if (candidates.isEmpty()) {
                    "the V10 aggregate $type has no 9.x aggregate in the map"
                } else {
                    "the V10 aggregate $type is mapped from several 9.x aggregates ${candidates.map { it.source }}"
                },
            )
        }
        val aggregate = candidates.single()
        return record.events.filter { eventOf(aggregate, it) == null }
            .map { "the V10 event ${it.name}@${it.revision} of $type has no 9.x event in the map" }
            .distinct()
    }

    /** [record] as the 9.x stream it goes back to; a [MigrationException] when [problemsOf] finds a problem. */
    fun revert(record: EventStreamRecord): NineXStream {
        problemsOf(record).firstOrNull()?.let { throw MigrationException("${record.ref} v${record.version}: $it.") }
        val aggregate = checkNotNull(aggregateOf(record.ref.type))
        val id = streamIdOf(record)
        val header = buildMap {
            record.headers.forEach { (name, value) -> headers[name]?.let { put(it, value) } }
            record.causation?.let { put(NineXHeaders.TRACE_ID, it.correlationId) }
            put(NineXHeaders.UPSTREAM_ID, record.commandId)
            put(NineXHeaders.UPSTREAM_NAME, commandOf(aggregate, record.commandType))
            record.operator?.let { put(NineXOperators.HEADER, it.id) }
        }
        return NineXStream(
            id = id,
            aggregate = aggregate.source,
            aggregateId = NineXAggregateId(record.ref.id, record.ref.tenantId),
            ownerId = record.scope.ownerId.orEmpty(),
            spaceId = record.scope.spaceId.orEmpty(),
            commandId = record.commandId,
            requestId = record.requestId,
            version = record.version,
            header = header,
            events = record.events.mapIndexed { index, event ->
                val mapping = checkNotNull(eventOf(aggregate, event))
                NineXEvent(
                    id = "$id-${index + 1}",
                    name = mapping.from,
                    revision = mapping.fromRevision ?: revisionOf(aggregate.source, mapping.from),
                    body = bodyOf(event, mapping),
                )
            },
            createTime = record.commitTime,
        )
    }

    private fun eventOf(aggregate: AggregatePlan, event: EventRecord): EventMapping? =
        aggregate.mapping.events.filter { mapping ->
            mapping.to?.let(aggregate::fullName) == event.name && mapping.toRevision == event.revision
        }.maxWithOrNull(compareBy<EventMapping> { it.fromRevision != null }.thenBy { it.fromRevision })

    private fun commandOf(aggregate: AggregatePlan, commandType: String): String =
        aggregate.mapping.commands.lastOrNull { aggregate.fullName(it.to) == commandType }?.from ?: commandType

    /** The latest revision of the 9.x event [name] the store holds; 9.x's default when it holds none. */
    private fun revisionOf(aggregate: NineXAggregate, name: String): String =
        revisionsOf(aggregate, name).maxOrNull() ?: NineXStream.DEFAULT_REVISION

    private fun bodyOf(event: EventRecord, mapping: EventMapping) = WowJson.mapper.readTree(event.payload).let { body ->
        if (mapping.fields.isEmpty() || body !is ObjectNode) {
            body
        } else {
            val names = mapping.fields.entries.associate { (from, to) -> to to from }
            WowJson.mapper.createObjectNode().also { renamed ->
                body.properties().forEach { (name, value) -> renamed.set(names[name] ?: name, value) }
            }
        }
    }

    companion object {
        /** The 9.x stream id of [record]: derived from its ref and version, so every run writes the same one. */
        fun streamIdOf(record: EventStreamRecord): String =
            UUID.nameUUIDFromBytes("${record.ref}@${record.version}".toByteArray()).toString().replace("-", "")
    }
}
