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

package me.ahoo.wow.query

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.CursorPositionCodec
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.observe.QueryObserver
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CopyOnWriteArrayList

/** What the tests of the gateway share: the gateway they build, its recording backend, and the observers and filters several of them use. */
internal object QueryGatewayFixtures {
    fun recordPrepare(record: (QueryContext<*>) -> Unit) = object : QueryFilter {
        override suspend fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Q {
            record(context)
            return context.query
        }
    }

    fun errorObserver(record: (Throwable) -> Unit) = object : QueryObserver {
        override fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) = record(error)
    }

    fun gateway(
        backend: QueryBackend,
        provider: QueryModelSchemaProvider = FixedQueryModelSchemaProvider(SCHEMA),
        filters: List<QueryFilter> = emptyList(),
        policies: List<QueryPolicy> = emptyList(),
        observer: QueryObserver = QueryObserver.NONE,
        entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
    ) = DefaultQueryGateway(AGGREGATE, backend, provider, filters, policies, observer, entryPolicy)

    /**
     * Answers a page with [row] (a keyset page with one row per position asked for), streams [rows], counts
     * [matches] and aggregates [groups]; records every admitted query and page window.
     */
    open class GatewayBackend(
        private val row: suspend () -> ObjectNode? = { null },
        private val rows: () -> Flow<ObjectNode> = { emptyFlow() },
        private val total: Long = 0,
        private val matches: Long = 0,
        private val groups: () -> Flow<ObjectNode> = { emptyFlow() },
    ) : QueryBackend {
        override val aggregateType: AggregateType = AGGREGATE
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON
        val admitted = CopyOnWriteArrayList<AdmittedQuery<*>>()
        val windows = CopyOnWriteArrayList<PageWindow>()
        val filters: List<FilterExpression>
            get() = admitted.map { (it.query as FilterCapable<*>).filter }

        override fun stream(query: AdmittedQuery<ListQuery>): Flow<ObjectNode> = flow {
            admitted += query
            emitAll(rows())
        }

        override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage {
            admitted += query
            windows += window
            return when (window) {
                is PageWindow.Keyset -> {
                    val found = (0 until window.limit).mapNotNull { row() }
                    BackendPage(found, positions = found.indices.map { CursorPosition(listOf("row-$it")) })
                }

                is PageWindow.Offset -> BackendPage(listOfNotNull(row()), total.takeIf { window.withTotal })
            }
        }

        override suspend fun count(query: AdmittedQuery<CountQuery>): Long {
            admitted += query
            return matches
        }

        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> = flow {
            admitted += query
            emitAll(groups())
        }
    }

    val AGGREGATE = AggregateType("context", "aggregate")
    val SCHEMA = gatewaySchema()

    fun snapshotNode(): ObjectNode = WowJson.mapper.readTree(
        """
        {"tenantId":"tenant","aggregateId":"aggregate","version":1,"ownerId":null,"spaceId":null,
         "deleted":false,"tags":{},"firstEventTime":1,"lastEventTime":1,"state":{"value":"state-value"}}
        """.trimIndent()
    ) as ObjectNode
}
