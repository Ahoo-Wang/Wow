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
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.CursorPositionCodec
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.TimeUnit

/** What the tests of `BackendQueries.kt` share: a bound schema per test, a recording backend and the rows it answers. */
internal abstract class BackendQueriesFixtures {
    protected val schema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "name" to scalarFixture(),
            "amount" to scalarFixture(QueryValueType.INTEGER),
            "createdAt" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS)),
        ),
    )

    protected class RecordingBackend(
        override val aggregateType: AggregateType = AggregateType("context", "aggregate"),
        private val pages: suspend (PageWindow) -> BackendPage = { BackendPage(emptyList(), 0, emptyList()) },
        private val groups: () -> Flow<ObjectNode> = { emptyFlow() },
        private val records: () -> Flow<ObjectNode> = { emptyFlow() },
        private val counts: suspend () -> Long = { 0 },
    ) : QueryBackend {
        val windows = mutableListOf<PageWindow>()
        val groupWindows = mutableListOf<GroupWindow>()
        val aggregations = mutableListOf<AggregationQuery>()
        val counted = mutableListOf<AdmittedQuery<CountQuery>>()
        var streams = 0
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON

        override fun stream(query: AdmittedQuery<ListQuery>): Flow<ObjectNode> = flow {
            streams++
            emitAll(records())
        }

        override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage {
            windows += window
            return pages(window)
        }

        override suspend fun count(query: AdmittedQuery<CountQuery>): Long {
            counted += query
            return counts()
        }

        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> = flow {
            aggregations += query.query
            groupWindows += window
            emitAll(groups())
        }
    }

    protected companion object {
        const val SLOW = 10_000L

        fun fail(error: Throwable): Nothing = throw error

        fun row(name: String): ObjectNode = WowJson.mapper.createObjectNode().put("name", name)

        fun json(text: String): ObjectNode = WowJson.mapper.readTree(text) as ObjectNode

        fun group(name: String, count: Long): ObjectNode = json("""{"name":"$name","count":$count}""")

        fun bucket(day: Long, count: Long): ObjectNode = json("""{"day":$day,"count":$count}""")

        fun QueryModelSchema.withStorage(storage: StorageSupport) =
            QueryModelSchema(
                model,
                capabilities,
                definition,
                bindings,
                fullProjectionAvailable,
                approximateMetrics,
                storage
            )
    }
}
