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

package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class ClickHouseStoreShapeValidatorTest {
    private val options = BiScriptOptions(
        consumerGroupNamespace = "test",
        topology = ClickHouseTopology.Standalone,
    )

    @Test
    fun `should accept the complete standalone command store shape`() {
        assertDoesNotThrow {
            ClickHouseStoreShapeValidator.validate(options, commandStore())
        }
    }

    @Test
    fun `should reject ordered column type drift`() {
        val store = commandStore(
            columns = commandColumns.map { column ->
                if (column.name == "tenant_id") column.copy(type = "UInt64") else column
            },
        )

        val failure = assertThrows<IllegalStateException> {
            ClickHouseStoreShapeValidator.validate(options, store)
        }

        failure.message.assert().contains("unexpected column schema", "tenant_id", "UInt64")
    }

    @Test
    fun `should reject state stores with obsolete key layout`() {
        assertThrows<IllegalStateException> {
            ClickHouseStoreShapeValidator.validate(
                options,
                stateStore(
                    name = "example_order_state_store",
                    sortingKey = "aggregate_id, version",
                ),
            )
        }.message.assert().contains("unexpected sorting key", "tenant_id, aggregate_id, version")
    }

    @Test
    fun `should require tenant aware sorting for current state stores`() {
        assertDoesNotThrow {
            ClickHouseStoreShapeValidator.validate(
                options,
                stateStore(
                    name = "example_order_state_store",
                    sortingKey = "tenant_id, aggregate_id, version",
                ),
            )
        }

        assertThrows<IllegalStateException> {
            ClickHouseStoreShapeValidator.validate(
                options,
                stateStore(
                    name = "example_order_state_store",
                    sortingKey = "aggregate_id, version",
                ),
            )
        }.message.assert().contains("tenant_id, aggregate_id, version")
    }

    @Test
    fun `should require tenant aware cluster sharding for current state stores`() {
        val clusterOptions = options.copy(
            topology = ClickHouseTopology.Cluster(name = "cluster", installation = "installation")
        )
        val v7Distributed = stateStore(
            name = "example_order_state_store",
            engine = "Distributed",
            engineFull = "Distributed('cluster', 'bi_db', 'example_order_state_store_local', " +
                "sipHash64(tenant_id, aggregate_id))",
            partitionKey = "",
            sortingKey = "",
        )

        assertDoesNotThrow {
            ClickHouseStoreShapeValidator.validate(clusterOptions, v7Distributed)
        }
        assertThrows<IllegalStateException> {
            ClickHouseStoreShapeValidator.validate(
                clusterOptions,
                v7Distributed.copy(
                    observed = v7Distributed.observed.copy(
                        engineFull = "Distributed('cluster', 'bi_db', 'example_order_state_store_local', " +
                            "sipHash64(aggregate_id))"
                    )
                ),
            )
        }.message.assert().contains("unexpected distributed engine definition")
    }

    @Test
    fun `should accept ClickHouse-expanded database and table replication macros`() {
        val clusterOptions = options.copy(
            topology = ClickHouseTopology.Cluster(name = "cluster", installation = "installation")
        )
        val localStore = stateStore(
            name = "example_order_state_store_local",
            engine = "ReplicatedReplacingMergeTree",
            engineFull = "ReplicatedReplacingMergeTree(" +
                "'/clickhouse/installation/cluster/tables/{shard}/bi_db/" +
                "example_order_state_store_local', '{replica}', version)",
            sortingKey = "tenant_id, aggregate_id, version",
        )

        assertDoesNotThrow {
            ClickHouseStoreShapeValidator.validate(clusterOptions, localStore)
        }
    }

    @Test
    fun `should reject engines that do not match the configured topology`() {
        val standaloneStore = commandStore()
        assertThrows<IllegalStateException> {
            ClickHouseStoreShapeValidator.validate(
                options,
                standaloneStore.copy(
                    observed = standaloneStore.observed.copy(engine = "MergeTree")
                ),
            )
        }.message.assert().contains("must use the ReplacingMergeTree engine")

        val clusterOptions = options.copy(
            topology = ClickHouseTopology.Cluster(name = "cluster", installation = "installation")
        )
        val localStore = stateStore(
            name = "example_order_state_store_local",
            engine = "ReplacingMergeTree",
            sortingKey = "tenant_id, aggregate_id, version",
        )
        assertThrows<IllegalStateException> {
            ClickHouseStoreShapeValidator.validate(clusterOptions, localStore)
        }.message.assert().contains("must use the ReplicatedReplacingMergeTree engine")

        val distributedStore = stateStore(
            name = "example_order_state_store",
            engine = "ReplacingMergeTree",
            partitionKey = "",
            sortingKey = "",
        )
        assertThrows<IllegalStateException> {
            ClickHouseStoreShapeValidator.validate(clusterOptions, distributedStore)
        }.message.assert().contains("must use the Distributed engine")
    }

    @Test
    fun `should reject local table keys on a distributed facade`() {
        val clusterOptions = options.copy(
            topology = ClickHouseTopology.Cluster(name = "cluster", installation = "installation")
        )
        val distributedStore = stateStore(
            name = "example_order_state_store",
            engine = "Distributed",
            engineFull = "Distributed('cluster', 'bi_db', 'example_order_state_store_local', " +
                "sipHash64(tenant_id, aggregate_id))",
            partitionKey = "toYYYYMM(create_time)",
            sortingKey = "tenant_id, aggregate_id, version",
        )

        assertThrows<IllegalStateException> {
            ClickHouseStoreShapeValidator.validate(clusterOptions, distributedStore)
        }.message.assert().contains("must not define local table keys")
    }

    private fun commandStore(columns: List<ClickHouseCatalogColumn> = commandColumns) = ClickHouseCatalogObject(
        observed = ObservedBiObject(
            database = options.database,
            name = "example_order_command_store",
            engine = "ReplacingMergeTree",
            engineFull = "ReplacingMergeTree PARTITION BY toYYYYMM(create_time) ORDER BY id SETTINGS x = 1",
        ),
        partitionKey = "toYYYYMM(create_time)",
        sortingKey = "id",
        columns = columns,
    )

    private fun stateStore(
        name: String,
        engine: String = "ReplacingMergeTree",
        engineFull: String = "ReplacingMergeTree(version)",
        partitionKey: String = "toYYYYMM(create_time)",
        sortingKey: String,
    ) = ClickHouseCatalogObject(
        observed = ObservedBiObject(
            database = options.database,
            name = name,
            engine = engine,
            engineFull = engineFull,
        ),
        partitionKey = partitionKey,
        sortingKey = sortingKey,
        columns = STATE_COLUMNS.mapIndexed { index, (columnName, type) ->
            ClickHouseCatalogColumn(
                database = options.database,
                table = name,
                name = columnName,
                type = type,
                position = index + 1,
            )
        },
    )

    private val commandColumns: List<ClickHouseCatalogColumn>
        get() = COMMAND_COLUMNS.mapIndexed { index, (name, type) ->
            ClickHouseCatalogColumn(
                database = options.database,
                table = "example_order_command_store",
                name = name,
                type = type,
                position = index + 1,
            )
        }

    private companion object {
        val COMMAND_COLUMNS = listOf(
            "id" to "String",
            "context_name" to "String",
            "aggregate_name" to "String",
            "name" to "String",
            "header" to "Map(String, String)",
            "aggregate_id" to "String",
            "tenant_id" to "String",
            "owner_id" to "String",
            "space_id" to "String",
            "request_id" to "String",
            "aggregate_version" to "Nullable(UInt32)",
            "is_create" to "Bool",
            "is_void" to "Bool",
            "allow_create" to "Bool",
            "body_type" to "String",
            "body" to "String",
            "create_time" to "DateTime64(3, 'Asia/Shanghai')",
        )
        val STATE_COLUMNS = listOf(
            "id" to "String",
            "context_name" to "String",
            "aggregate_name" to "String",
            "header" to "Map(String, String)",
            "aggregate_id" to "String",
            "tenant_id" to "String",
            "owner_id" to "String",
            "space_id" to "String",
            "command_id" to "String",
            "request_id" to "String",
            "version" to "UInt32",
            "state" to "String",
            "body" to "Array(String)",
            "first_operator" to "String",
            "first_event_time" to "DateTime64(3, 'Asia/Shanghai')",
            "create_time" to "DateTime64(3, 'Asia/Shanghai')",
            "tags" to "Map(String, Array(String))",
            "deleted" to "Bool",
        )
    }
}
