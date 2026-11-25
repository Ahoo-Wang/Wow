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

package me.ahoo.wow.query.memory

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec
import me.ahoo.wow.query.tck.DatasetTable
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class InMemoryDatasetQueryBackendTest : DatasetQueryBackendSpec() {
    private val storage = InMemoryDatasetStorage()

    override fun createDatasetBindings(): InMemoryDatasetStorage = storage

    override suspend fun createTable(table: DatasetTable): ObjectNode = storage.put(table.name, table.rows.map(::row))

    @Test
    fun `rows are copies, so a projection never changes the table`() {
        val binding = storage.put("copies", listOf(WowJson.mapper.createObjectNode().put("id", "r1")))
        binding.assert().isEqualTo(InMemoryDatasetStorage.binding("copies"))
        storage.rows("copies").single().remove("id")
        storage.rows("copies").single().path("id").stringValue().assert().isEqualTo("r1")
        storage.rows("missing").assert().isEmpty()
    }

    @Test
    fun `a dataset without an in-memory table binding is refused`() {
        val unbound = QueryDataset.of(AggregateType("tck", "unbound")) {
            tenant("tenant_id")
            field("tenant_id") { types(QueryValueType.STRING) }
        }
        assertThrows<IllegalArgumentException> { storage.binding(unbound) }
    }

    @Test
    fun `the backend records the timeout of each query`() {
        runBlocking {
            val orders = target(ORDERS)
            val backend = orders.backend as InMemoryQueryBackend
            val timeout = Duration.ofSeconds(3)
            val count = QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), orders.schema).withTimeout(timeout)
            backend.countBounded(count).count.assert().isEqualTo(6L)
            backend.timeouts.assert().containsExactly(timeout)
        }
    }

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
