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

import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.query.DatasetQueryBackendSpec
import me.ahoo.wow.tck.query.DatasetTable
import tools.jackson.databind.node.ObjectNode

/** The dataset TCK on ClickHouse. */
class ClickHouseDatasetQueryBackendTest : DatasetQueryBackendSpec() {
    override fun createDatasetQueryBackendFactory(): DatasetQueryBackendFactory =
        ClickHouseDatasetQueryBackendFactory(ClickHouseExecutor(ClickHouseContainers.client))

    override fun createTable(table: DatasetTable): ObjectNode {
        ClickHouseContainers.load(table)
        return JsonSerializer.createObjectNode().put("table", "${ClickHouseContainers.DATABASE}.${table.name}")
    }

    /**
     * A NULL is a table's only missing value (`AbsentValues.AS_MISSING`), so EXISTS cannot tell an explicit null from
     * a missing field, as on Elasticsearch.
     */
    override val semanticDivergences: Set<String> = setOf("string.exists", "string.not-exists")
}
