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
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec
import me.ahoo.wow.query.tck.DatasetTable
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.node.ObjectNode

/** The dataset TCK on ClickHouse. */
class ClickHouseDatasetQueryBackendTest : DatasetQueryBackendSpec() {
    override fun createDatasetQueryBackendFactory(): DatasetQueryBackendFactory =
        ClickHouseDatasetQueryBackendFactory(ClickHouseContainers.client)

    override suspend fun createTable(table: DatasetTable): ObjectNode {
        ClickHouseContainers.load(table)
        return WowJson.mapper.createObjectNode().put("table", "${ClickHouseContainers.DATABASE}.${table.name}")
    }

    /**
     * The 9.x divergences, all still in force: a NULL is a table's only missing value (`AbsentValues.AS_MISSING`), so
     * EXISTS cannot tell an explicit null from a missing field, and an array column stores a missing or null value as
     * an empty array, so presence and emptiness cannot tell the three apart; both as on Elasticsearch. Exact array
     * equality is not supported (`arrayEquality` NONE).
     */
    override val semanticDivergences: Set<String> = setOf(
        "string.exists",
        "string.not-exists",
        "array.is-empty",
        "array.is-null",
        "array.is-not-null",
        "array.exists",
        "array.not-exists",
        "array.eq-array",
    )
}
