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

package me.ahoo.wow.clickhouse.query

import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.clickhouse.sink.ClickHouseColumn
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.spi.json.WowJson
import java.time.Duration
import java.util.concurrent.TimeUnit

/** A table of every column family and the dataset declared over it, compiled without a server. */
internal object ClickHouseQueryFixtures {
    val TABLE = ClickHouseTable(
        "bi",
        "orders",
        "MergeTree",
        listOf(
            ClickHouseColumn("paid", "Bool"),
            ClickHouseColumn("tags", "Array(String)"),
            ClickHouseColumn("attrs", "Map(String, String)"),
            ClickHouseColumn("id", "String"),
            ClickHouseColumn("tenant_id", "String"),
            ClickHouseColumn("owner_id", "String"),
            ClickHouseColumn("space_id", "String"),
            ClickHouseColumn("city", "LowCardinality(String)"),
            ClickHouseColumn("note", "Nullable(String)"),
            ClickHouseColumn("amount", "Decimal(18, 2)"),
            ClickHouseColumn("rate", "Float64"),
            ClickHouseColumn("quantity", "Int64"),
            ClickHouseColumn("created_at", "DateTime64(3, 'UTC')"),
            ClickHouseColumn("paid_at", "Nullable(DateTime64(3, 'UTC'))"),
            ClickHouseColumn("day", "Date"),
            ClickHouseColumn("epoch_ms", "Int64"),
            ClickHouseColumn("epoch_s", "Int64"),
            ClickHouseColumn("epoch_us", "Int64"),
        ),
    )

    val DATASET: QueryDataset = QueryDataset.of(AggregateType("bi", "orders")) {
        tenant("tenant_id")
        owner("owner_id")
        space("space_id")
        identity("id")
        eventTime("created_at")
        storage(WowJson.mapper.createObjectNode().put("table", "bi.orders"))
        listOf("id", "tenant_id", "owner_id", "space_id", "city").forEach {
            field(it) {
                types(QueryValueType.STRING)
                nullable(false)
            }
        }
        field("note") { types(QueryValueType.STRING) }
        tag("label", "tags")
        field("tags") {
            kind(QueryValueKind.ARRAY)
            nullable(false)
            items { types(QueryValueType.STRING) }
        }
        field("paid") {
            types(QueryValueType.BOOLEAN)
            nullable(false)
        }
        listOf("amount", "rate").forEach {
            field(it) {
                types(QueryValueType.DECIMAL)
                nullable(false)
            }
        }
        field("quantity") {
            types(QueryValueType.INTEGER)
            nullable(false)
        }
        listOf("created_at", "day").forEach {
            field(it) {
                types(QueryValueType.STRING)
                nullable(false)
                semantic(Temporal.Date)
            }
        }
        field("paid_at") {
            types(QueryValueType.STRING)
            semantic(Temporal.Date)
        }
        mapOf("epoch_ms" to TimeUnit.MILLISECONDS, "epoch_s" to TimeUnit.SECONDS, "epoch_us" to TimeUnit.MICROSECONDS)
            .forEach { (name, unit) ->
                field(name) {
                    types(QueryValueType.INTEGER)
                    nullable(false)
                    temporalEpoch(unit)
                }
            }
    }

    fun adapter(
        dataset: QueryDataset = DATASET,
        storage: ClickHouseDatasetStorage = ClickHouseDatasetStorage("bi", "orders"),
        table: suspend () -> ClickHouseTable = { TABLE },
    ) = ClickHouseQuerySchemaAdapter(dataset, storage, ClickHouseTableMetadata(Duration.ZERO, load = table))

    fun schema(
        adapter: ClickHouseQuerySchemaAdapter,
        dataset: QueryDataset = DATASET,
    ): QueryModelSchema = runBlocking {
        QueryModelCompiler.of(datasets = QueryDatasets(listOf(dataset)))
            .compile(QuerySchemaContext(dataset.name, QueryModel.DATASET), adapter)
            .schema()
    }

    fun schema(dataset: QueryDataset, storage: ClickHouseDatasetStorage = ClickHouseDatasetStorage("bi", "orders")) =
        schema(adapter(dataset, storage), dataset)

    val SCHEMA: QueryModelSchema by lazy { schema(adapter()) }
}
