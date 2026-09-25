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

package me.ahoo.wow.elasticsearch.eventsourcing

import co.elastic.clients.elasticsearch._types.SortOptions
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.bool
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.range
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.term
import co.elastic.clients.json.JsonData
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.serialization.MessageRecords

/**
 * The event store's native searches over its own event stream index (design §5.5): built directly from the typed
 * arguments of the event store operations, on the physical fields the event store writes. They never pass through
 * query admission or the query compilers, and take no caller-supplied filter.
 */
internal object EventStreamSearches {
    /** The streams of [aggregateId] whose version lies in `[headVersion, tailVersion]`. */
    fun versionRange(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Query =
        aggregate(aggregateId, between(MessageRecords.VERSION, headVersion, tailVersion))

    /** The streams of [aggregateId] created in `[headEventTime, tailEventTime]`. */
    fun timeRange(aggregateId: AggregateId, headEventTime: Long, tailEventTime: Long): Query =
        aggregate(aggregateId, between(MessageRecords.CREATE_TIME, headEventTime, tailEventTime))

    /** Every stream of [aggregateId]. */
    fun all(aggregateId: AggregateId): Query = aggregate(aggregateId)

    /** The first stream of each aggregate whose id sorts after [afterId]. */
    fun initialStreamsAfter(afterId: String): Query = bool {
        it.filter(
            after(MessageRecords.AGGREGATE_ID, afterId),
            term { term -> term.field(MessageRecords.VERSION).value(Version.INITIAL_VERSION.toLong()) },
        )
    }

    /** Version order with the stream id as tie-breaker, ascending or descending. */
    fun versionOrder(descending: Boolean): List<SortOptions> {
        val order = if (descending) SortOrder.Desc else SortOrder.Asc
        return listOf(MessageRecords.VERSION, MessageRecords.ID).map { field -> fieldOrder(field, order) }
    }

    val aggregateIdOrder: List<SortOptions> = listOf(fieldOrder(MessageRecords.AGGREGATE_ID, SortOrder.Asc))

    private fun aggregate(aggregateId: AggregateId, vararg conditions: Query): Query = bool {
        it.filter(
            listOf(
                term { term -> term.field(MessageRecords.TENANT_ID).value(aggregateId.tenantId) },
                term { term -> term.field(MessageRecords.AGGREGATE_ID).value(aggregateId.id) },
            ) + conditions,
        )
    }

    private fun after(field: String, value: String): Query = range {
        it.untyped { range -> range.field(field).gt(JsonData.of(value)) }
    }

    private fun between(field: String, lower: Number, upper: Number): Query = range {
        it.untyped { range -> range.field(field).gte(JsonData.of(lower)).lte(JsonData.of(upper)) }
    }

    private fun fieldOrder(field: String, order: SortOrder): SortOptions =
        SortOptions.of { sort -> sort.field { it.field(field).order(order) } }
}
