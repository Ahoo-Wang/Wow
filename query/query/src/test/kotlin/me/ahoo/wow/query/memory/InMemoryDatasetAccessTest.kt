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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.query.tck.DatasetAccessSpec
import me.ahoo.wow.query.tck.DatasetTable
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.node.ObjectNode

/** The dataset access TCK on the in-memory dataset storage. */
class InMemoryDatasetAccessTest : DatasetAccessSpec() {
    private val storage = InMemoryDatasetStorage()

    override fun createDatasetBindings(): InMemoryDatasetStorage = storage

    override suspend fun createTable(table: DatasetTable): ObjectNode =
        storage.put(
            table.name,
            table.rows.map { values ->
                WowJson.mapper.createObjectNode().also { row ->
                    values.forEach { (column, value) -> row.put(column, value as String) }
                }
            },
        )

    override suspend fun storeAccess(aggregate: AggregateType, records: List<AccessRecord>) {
        records.forEach { storage.putAccess(aggregate, it.tenantId, it.aggregateId, it.version, it.access) }
    }
}
