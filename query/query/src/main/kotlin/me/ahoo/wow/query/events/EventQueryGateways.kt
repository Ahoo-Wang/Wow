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

package me.ahoo.wow.query.events

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryCountCache
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.QueryRecordReader
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.json.query.EventFields
import me.ahoo.wow.spi.json.query.EventStreamFields
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.tenant.TenantPolicy
import tools.jackson.databind.node.ObjectNode

/**
 * The `events` query model of an aggregate type on the unified engine (decision D235, revising D131): a
 * [DefaultQueryGateway] over the type's events [backend], admitted against the schema [schemaProvider] compiles
 * ([me.ahoo.wow.query.schema.EventsQuerySchemas]), under the same filters, policies, observers, budgets and count cache
 * as its snapshots.
 *
 * - **Record.** One event stream, the commit of one command: `aggregateId`, `tenantId`, `version`, `commitTime`
 *   (epoch milliseconds), `commandId`, `requestId`, `commandType`, `ownerId` and `spaceId` (null when unset),
 *   `operator` (`{id, tenantId, crossScope}`, null for an anonymous caller, decision D163), `tags` (after the commit,
 *   null when the log did not record them, decision D259) and `events`, each
 *   `{name, revision, body}`. Its identity is `(aggregateId, version)`; `count` counts streams; an event condition is
 *   an `ELEMENT_MATCH` on `events`, and per-event analytics expand it (`AggregationQuery.elements`).
 * - **Reading.** The backend returns streams as stored ([EventStreamRecords.stored]); each record is read as
 *   [EventStreamRecords.reader] says: every event as the aggregate's model reads it now ([reading], finding A4-16), an
 *   event the aggregate retired left out (decision D219), and each body masked by its event's schema ([masks], 09 §17
 *   G-2), for every caller alike.
 * - **Visibility.** Each policy declares how it restricts the streams ([QueryPolicy.events], decisions D254, D259).
 *   While a pinned one applies, an aggregate the snapshot queries of its type hide from the caller has no streams for
 *   it ([visibility], reviews A3-11, R2.3-6): [EventVisibilityPolicy] joins the policy step, and the descriptor lists
 *   [AGGREGATE_REQUIRED][UnpinnedEventsPolicy.AGGREGATE_REQUIRED], as under [UnpinnedEvents.REFUSE].
 * - **Counts.** [counts] must be the events model's own cache: a cache is keyed by aggregate type, not by model.
 *
 * Free filtering reads the write store: the PostgreSQL and MongoDB logs index only `(aggregateId, tenantId, version)`,
 * `(tenantId, requestId)` and `(partition, sequence)`, so a condition without the aggregate scans the type's log. The
 * entry's [QueryBudget][me.ahoo.wow.query.QueryBudget] (sizes, count limit, timeout) bounds it, the store's
 * [QueryBulkhead][me.ahoo.wow.query.QueryBulkhead] bounds how many run at once, and [unpinned] may refuse it
 * ([UnpinnedEvents.REFUSE], review R3.4-2); an analytics-heavy deployment serves the model from ClickHouse or
 * Elasticsearch.
 */
@InternalWowApi
object EventQueryGateways {
    /** The model's name, the `{model}` segment of its route. */
    const val MODEL: String = "events"

    @JvmStatic
    @Suppress("LongParameterList") // One parameter per part of a gateway, as DefaultQueryGateway's.
    fun create(
        aggregateType: AggregateType,
        backend: QueryBackend,
        schemaProvider: QueryModelSchemaProvider,
        filters: List<QueryFilter> = emptyList(),
        policies: List<QueryPolicy> = emptyList(),
        observer: QueryObserver = QueryObserver.NONE,
        entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
        counts: QueryCountCache? = null,
        visibility: EventVisibility = EventVisibility.ALL,
        tenants: TenantPolicy = TenantPolicy.CALLERS,
        masks: PayloadMasks = PayloadMasks.NONE,
        reading: (EventRecord) -> EventRecord? = { it },
        unpinned: UnpinnedEvents = UnpinnedEvents.ALLOW,
    ): QueryGateway {
        val refuses = unpinned == UnpinnedEvents.REFUSE
        val pinned = refuses || visibility.pins(aggregateType)
        return DefaultQueryGateway(
            aggregateType = aggregateType,
            backend = backend,
            schemaProvider = schemaProvider,
            filters = filters,
            policies = policies + listOfNotNull(
                UnpinnedEventsPolicy().takeIf { refuses },
                EventVisibilityPolicy(visibility, tenants, entryPolicy),
            ),
            observer = observer,
            entryPolicy = entryPolicy,
            counts = counts,
            records = EventStreamRecords.reader(aggregateType, masks, reading),
            constraints = listOfNotNull(UnpinnedEventsPolicy.CONSTRAINT.takeIf { pinned }),
        )
    }
}

/** The records of the `events` model (decision D235): a stream as stored, and how the gateway reads one. */
@InternalWowApi
object EventStreamRecords {
    /**
     * [stream] as an events backend returns it: its facts under the model's names ([EventStreamFields]), each event
     * as stored, `{name, revision, body}`. A memory backend builds its records so; a log backend reads the same shape.
     */
    @JvmStatic
    fun stored(stream: EventStreamRecord): ObjectNode {
        val record = WowJson.mapper.createObjectNode()
            .put(EventStreamFields.AGGREGATE_ID, stream.ref.id)
            .put(EventStreamFields.TENANT_ID, stream.ref.tenantId)
            .put(EventStreamFields.VERSION, stream.version)
            .put(EventStreamFields.COMMIT_TIME, stream.commitTime)
            .put(EventStreamFields.COMMAND_ID, stream.commandId)
            .put(EventStreamFields.REQUEST_ID, stream.requestId)
            .put(EventStreamFields.COMMAND_TYPE, stream.commandType)
            .put(EventStreamFields.OWNER_ID, stream.scope.ownerId)
            .put(EventStreamFields.SPACE_ID, stream.scope.spaceId)
        val operator = stream.operator
        if (operator == null) {
            record.putNull(EventStreamFields.OPERATOR)
        } else {
            record.putObject(EventStreamFields.OPERATOR)
                .put("id", operator.id)
                .put("tenantId", operator.tenantId)
                .put("crossScope", operator.crossScope)
        }
        val tags = stream.tags
        if (tags == null) {
            record.putNull(EventStreamFields.TAGS)
        } else {
            val node = record.putObject(EventStreamFields.TAGS)
            tags.forEach { (key, values) -> node.putArray(key).also { array -> values.forEach(array::add) } }
        }
        val events = record.putArray(EventStreamFields.EVENTS)
        stream.events.forEach { event ->
            events.addObject()
                .put(EventFields.NAME, event.name)
                .put(EventFields.REVISION, event.revision)
                .set(EventFields.BODY, WowJson.mapper.readTree(event.payload))
        }
        return record
    }

    /**
     * Reads a stored record of [type] as the model shows it: each event as [reading] gives it (its current name and
     * revision, its body upgraded; null leaves it out), its body masked by [masks]. A record without `events` (a
     * projection dropped them) is returned as it is.
     */
    @JvmStatic
    fun reader(
        type: AggregateType,
        masks: PayloadMasks = PayloadMasks.NONE,
        reading: (EventRecord) -> EventRecord? = { it },
    ): QueryRecordReader = QueryRecordReader { record ->
        val stored = record.get(EventStreamFields.EVENTS)
        if (stored != null && stored.isArray) {
            val events = record.putArray(EventStreamFields.EVENTS)
            stored.forEach { event ->
                val read = reading(
                    EventRecord(
                        event.get(EventFields.NAME).asString(),
                        event.get(EventFields.REVISION).asInt(),
                        WowJson.mapper.writeValueAsString(event.get(EventFields.BODY)),
                    ),
                ) ?: return@forEach
                events.addObject()
                    .put(EventFields.NAME, read.name)
                    .put(EventFields.REVISION, read.revision)
                    .set(EventFields.BODY, masks.event(type, read.name, WowJson.mapper.readTree(read.payload)))
            }
        }
        record
    }
}
