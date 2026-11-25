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

package me.ahoo.wow.query.tck

import kotlinx.coroutines.flow.Flow
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.tck.SnapshotAggregationCases.Companion.SUBNORMAL_REFUSED
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import tools.jackson.databind.node.ObjectNode
import kotlin.math.abs

/**
 * The snapshot contract on a backend that reads no subnormal double, as ClickHouse reads none (#7009): the memory
 * backend behind [SubnormalRefusingBackend], listing [SUBNORMAL_REFUSED]. The subnormal constant's case takes its
 * refusal branch here, and its computed one on the memory backend's own subclass; every other case answers as there.
 */
class SubnormalRefusingQueryBackendTest : QueryBackendSpec() {
    private val storage = InMemoryQueryStorage()

    override val semanticDivergences: Set<String> = setOf(SUBNORMAL_REFUSED)

    override suspend fun store(snapshots: List<SnapshotRecord>) {
        snapshots.forEach(storage::save)
    }

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> =
        storage.binding(type).let { QueryBackendBinding(SubnormalRefusingBackend(it.backend), it.storage) }
}

/**
 * [backend], but a query whose filter compares an expression with a subnormal double is refused as storage-unsupported
 * before it runs, as the ClickHouse backend refuses one.
 */
internal class SubnormalRefusingBackend(private val backend: QueryBackend) : QueryBackend by backend {
    override fun stream(query: AdmittedQuery<ListQuery>): Flow<ObjectNode> = backend.stream(query.refusing())

    override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage =
        backend.page(query.refusing(), window)

    override suspend fun count(query: AdmittedQuery<CountQuery>): Long = backend.count(query.refusing())

    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> =
        backend.aggregate(query.refusing(), window)

    private fun <Q : FilterCapable<*>> AdmittedQuery<Q>.refusing(): AdmittedQuery<Q> = apply {
        val filter = query.filter
        if (filter is ExpressionFilter && filter.value != 0.0 && abs(filter.value) < java.lang.Double.MIN_NORMAL) {
            throw QueryViolation.StorageUnsupported("the subnormal double constant ${filter.value}").rejection()
        }
    }
}
