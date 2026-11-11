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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.clickhouse.sink.AggregateSinkOptions
import me.ahoo.wow.clickhouse.sink.ClickHouseRows
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkSchema
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.tck.DatasetAccessSpec
import me.ahoo.wow.query.tck.DatasetTable
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import tools.jackson.databind.node.ObjectNode

/**
 * The dataset access TCK on ClickHouse: the access records are the rows the Sink writes for each commit into the
 * aggregate's `_state` ([ClickHouseRows]), created by the Sink's own DDL without the history, as a Sink without it
 * inserts them; one insert each, so the rows are merged only by `FINAL`.
 */
class ClickHouseDatasetAccessTest : DatasetAccessSpec() {
    override fun createDatasetBindings(): QueryBindings<QueryDataset> =
        ClickHouseDatasetBindings(ClickHouseContainers.client, ClickHouseDatasetOptions(ClickHouseContainers.DATABASE))

    override suspend fun createTable(table: DatasetTable): ObjectNode {
        ClickHouseContainers.load(table)
        return WowJson.mapper.createObjectNode().put("table", "${ClickHouseContainers.DATABASE}.${table.name}")
    }

    override suspend fun storeAccess(aggregate: AggregateType, records: List<AccessRecord>) {
        val schema = ClickHouseSinkSchema(
            aggregate,
            ClickHouseContainers.DATABASE,
            AggregateSinkOptions(history = false),
            emptyList(),
        )
        ClickHouseContainers.execute("DROP TABLE IF EXISTS ${schema.qualified(schema.state)}")
        schema.ddl.forEach(ClickHouseContainers::execute)
        records.forEach { record ->
            val row = StateEventRecord(
                ref = aggregate.ref(record.tenantId, record.aggregateId),
                version = record.version,
                commandId = "command-${record.version}",
                requestId = "request-${record.version}",
                commandType = "tck.document.Change",
                commitTime = COMMIT_TIME + record.version,
                events = listOf(StateEventPayload("document_changed", 1, WowJson.mapper.createObjectNode())),
                access = record.access,
            )
            val body = ClickHouseRows.bodyOf(listOf(row), history = false).toString(Charsets.UTF_8)
            ClickHouseContainers.execute("INSERT INTO ${schema.qualified(schema.state)} FORMAT JSONEachRow $body")
        }
    }

    private companion object {
        const val COMMIT_TIME = 1_760_000_000_000L
    }
}
