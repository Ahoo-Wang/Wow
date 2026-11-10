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

import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.policy.ScopeQueryPolicy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * The dataset backend contract (the 9.x `DatasetQueryBackendSpec` of wow-tck): every dataset query
 * backend passes it over a flat table: the filter semantics matrix, records (sort, projection, offset and keyset
 * pages, counts), aggregation, the dataset's tenant column, and the gateway's scope over it. A backend's test supplies
 * its bindings ([createDatasetBindings]) and loads each table in [createTable].
 *
 * Queries run through trusted admission against the schema the Catalog compiles from the dataset's declaration and
 * the storage's facts, as a gateway would but without governance, except the gateway test.
 *
 * The cases come in families, each a parent class of this one ([DatasetQueryFixtures] lists them); this class
 * holds the gateway case and the tables.
 */
abstract class DatasetQueryBackendSpec : DatasetDateBucketCases() {
    @Test
    fun `the gateway restricts every query to the caller's tenant column`() = blocking {
        val datasets = QueryDatasets(listOf(orders.dataset))
        val gateways = DatasetQueryGateways.of(
            datasets,
            catalog(datasets),
            bindings,
            policies = listOf(ScopeQueryPolicy())
        )
        val gateway = checkNotNull(gateways[orders.dataset.name])
        val t2 = QueryCaller(Caller(tenantId = "t2"), entry = QueryEntry.HTTP)
        gateway.count(CountQuery(MatchAllFilter), t2).assert().isEqualTo(CountResult(1, exact = true))
        gateway.list(ListQuery(MatchAllFilter, limit = 10), t2).toList().map { it.path(ID).stringValue() }
            .assert().containsExactly("o6")
        gateway.single(SingleQuery(IdsFilter(listOf("o1"))), t2).assert().isNull()
        gateway.count(
            CountQuery(MatchAllFilter),
            QueryCaller.IN_PROCESS
        ).assert().isEqualTo(CountResult(6, exact = true))
        Unit
    }

    companion object {
        const val CONTEXT: String = "tck"
        const val ID: String = "id"
        internal const val MAX_LIST = 100
        internal const val EPSILON = 1e-9
        internal val TENANT_COLUMN = QueryField("tenant_id")
        internal val CITY = QueryField("city")
        internal val AMOUNT = QueryField("amount")
        internal val CREATED_AT = QueryField("created_at")

        /** The column each semantic shape's probes are stored in. */
        val SEMANTIC_FIELDS: Map<SemanticShape, String> = mapOf(
            SemanticShape.STRING to "string_value",
            SemanticShape.NUMBER to "number_value",
            SemanticShape.STRING_ARRAY to "array_value",
        )

        /**
         * One row per probe of each shape, keyed by [QueryBackendSpec.probeId]: a missing probe leaves its column out
         * of the row, a null probe stores `null`.
         */
        val SEMANTIC = DatasetTable(
            name = "semantic",
            columns = listOf(
                DatasetColumn(ID, DatasetColumn.Type.STRING),
                DatasetColumn(TENANT_COLUMN.path, DatasetColumn.Type.STRING),
                DatasetColumn("string_value", DatasetColumn.Type.STRING, nullable = true),
                DatasetColumn("number_value", DatasetColumn.Type.INTEGER, nullable = true),
                DatasetColumn("array_value", DatasetColumn.Type.STRING_ARRAY, nullable = true),
            ),
            rows = SEMANTIC_FIELDS.flatMap { (shape, column) ->
                checkNotNull(SemanticProbe.ALL[shape]).map { probe ->
                    val row = mapOf(ID to QueryBackendSpec.probeId(probe), TENANT_COLUMN.path to "t1")
                    val json = probe.json ?: return@map row
                    row + (column to probeValue(json))
                }
            },
        )

        private fun probeValue(json: String): Any? {
            val node = me.ahoo.wow.spi.json.WowJson.mapper.readTree(json)
            return when {
                node.isNull -> null
                node.isArray -> node.toList().map { it.stringValue() }
                node.isNumber -> node.longValue()
                else -> node.stringValue()
            }
        }

        val ORDERS = DatasetTable(
            name = "orders",
            columns = listOf(
                DatasetColumn(ID, DatasetColumn.Type.STRING),
                DatasetColumn(TENANT_COLUMN.path, DatasetColumn.Type.STRING),
                DatasetColumn("city", DatasetColumn.Type.STRING),
                DatasetColumn("amount", DatasetColumn.Type.DECIMAL),
                DatasetColumn("quantity", DatasetColumn.Type.INTEGER),
                DatasetColumn("discount", DatasetColumn.Type.DECIMAL, nullable = true),
                DatasetColumn("created_at", DatasetColumn.Type.DATE),
            ),
            rows = listOf(
                order("o1", "t1", "Hangzhou", "10.50", 1, null, "2025-01-01T01:00:00Z"),
                order("o2", "t1", "Hangzhou", "20.25", 2, "0.10", "2025-01-01T23:30:00Z"),
                order("o3", "t1", "Beijing", "30.00", 3, "0.20", "2025-01-02T10:00:00Z"),
                order("o4", "t1", "Beijing", "5.00", 1, null, "2025-02-01T00:00:00Z"),
                order("o5", "t1", "Shanghai", "7.75", 4, "0.05", "2025-02-15T12:00:00Z"),
                order("o6", "t2", "Hangzhou", "99.99", 9, null, "2025-01-01T05:00:00Z"),
            ),
        )

        /** Instants a microsecond apart, within one millisecond. */
        val MICROS = DatasetTable(
            name = "micros",
            columns = listOf(
                DatasetColumn(ID, DatasetColumn.Type.STRING),
                DatasetColumn(TENANT_COLUMN.path, DatasetColumn.Type.STRING),
                DatasetColumn("created_at", DatasetColumn.Type.DATE),
            ),
            rows = listOf(
                "u1" to "2026-01-01T00:00:00.000200Z",
                "u2" to "2026-01-01T00:00:00.000100Z",
                "u3" to "2026-01-01T00:00:00.000300Z",
            ).map { (id, at) -> mapOf(ID to id, TENANT_COLUMN.path to "t1", "created_at" to Instant.parse(at)) },
        )

        /** Instants on either side of the epoch. */
        val HISTORY = DatasetTable(
            name = "history",
            columns = listOf(
                DatasetColumn(ID, DatasetColumn.Type.STRING),
                DatasetColumn(TENANT_COLUMN.path, DatasetColumn.Type.STRING),
                DatasetColumn("created_at", DatasetColumn.Type.DATE),
            ),
            rows = listOf(
                "h1" to "1969-12-31T12:34:56Z",
                "h2" to "1969-12-31T12:10:00Z",
                "h3" to "1970-01-01T00:30:00Z",
            ).map { (id, at) -> mapOf(ID to id, TENANT_COLUMN.path to "t1", "created_at" to Instant.parse(at)) },
        )

        /** Instants a millisecond apart, whose ISO texts order otherwise than their times. */
        val DATES = DatasetTable(
            name = "dates",
            columns = listOf(
                DatasetColumn(ID, DatasetColumn.Type.STRING),
                DatasetColumn(TENANT_COLUMN.path, DatasetColumn.Type.STRING),
                DatasetColumn("created_at", DatasetColumn.Type.DATE),
            ),
            rows = listOf(
                "d1" to "2025-03-01T00:00:00.001Z",
                "d2" to "2025-03-01T00:00:00Z",
                "d3" to "2025-03-01T00:00:00.010Z",
            ).map { (id, at) -> mapOf(ID to id, TENANT_COLUMN.path to "t1", "created_at" to Instant.parse(at)) },
        )

        @Suppress("LongParameterList")
        private fun order(
            id: String,
            tenant: String,
            city: String,
            amount: String,
            quantity: Long,
            discount: String?,
            createdAt: String,
        ): Map<String, Any?> = mapOf(
            ID to id,
            TENANT_COLUMN.path to tenant,
            "city" to city,
            "amount" to BigDecimal(amount),
            "quantity" to quantity,
            "discount" to discount?.let(::BigDecimal),
            "created_at" to Instant.parse(createdAt),
        )
    }
}
