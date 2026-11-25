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

import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.memory.InMemoryDatasetStorage
import me.ahoo.wow.query.tck.SnapshotAggregationCases.Companion.SUBNORMAL_REFUSED
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.Instant

/**
 * The dataset contract on a backend that reads no subnormal double, as ClickHouse reads none (#7009): the memory
 * backend behind [SubnormalRefusingBackend], listing [SUBNORMAL_REFUSED]. The subnormal constant's case takes its
 * refusal branch here, and its computed one on the memory backend's own subclass; every other case answers as there.
 */
class SubnormalRefusingDatasetQueryBackendTest : DatasetQueryBackendSpec() {
    private val storage = InMemoryDatasetStorage()

    override val semanticDivergences: Set<String> = setOf(SUBNORMAL_REFUSED)

    override fun createDatasetBindings(): QueryBindings<QueryDataset> = QueryBindings { dataset ->
        storage.binding(dataset)?.let { QueryBackendBinding(SubnormalRefusingBackend(it.backend), it.storage) }
    }

    override suspend fun createTable(table: DatasetTable): ObjectNode = storage.put(table.name, table.rows.map(::row))

    private fun row(values: Map<String, Any?>): ObjectNode = WowJson.mapper.createObjectNode().also { row ->
        values.forEach { (column, value) ->
            when (value) {
                null -> row.putNull(column)
                is String -> row.put(column, value)
                is Long -> row.put(column, value)
                is BigDecimal -> row.put(column, value)
                is Instant -> row.put(column, value.toString())
                is List<*> -> row.putArray(column).also { array -> value.forEach { array.add(it as String) } }
                else -> error("Unsupported TCK value [$value].")
            }
        }
    }
}
