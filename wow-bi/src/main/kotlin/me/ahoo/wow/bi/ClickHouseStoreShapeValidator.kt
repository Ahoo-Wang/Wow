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

import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax

internal object ClickHouseStoreShapeValidator {
    private const val LOCAL_TABLE_SUFFIX: String = "_local"
    private const val REPLACING_MERGE_TREE_ENGINE: String = "ReplacingMergeTree"
    private const val REPLICATED_REPLACING_MERGE_TREE_ENGINE: String = "ReplicatedReplacingMergeTree"
    private const val DISTRIBUTED_ENGINE: String = "Distributed"

    fun validate(options: BiScriptOptions, store: ClickHouseCatalogObject) {
        val observed = store.observed
        val physicalName = when (options.topology) {
            ClickHouseTopology.Standalone -> observed.name
            is ClickHouseTopology.Cluster -> observed.name.removeSuffix(LOCAL_TABLE_SUFFIX)
        }
        val layout = StoreLayout.from(physicalName)
        when (val topology = options.topology) {
            ClickHouseTopology.Standalone -> validateStandalone(options, store, layout)
            is ClickHouseTopology.Cluster -> validateCluster(options, topology, store, layout)
        }
    }

    private fun validateStandalone(
        options: BiScriptOptions,
        store: ClickHouseCatalogObject,
        layout: StoreLayout,
    ) {
        val observed = store.observed
        check(observed.engine == REPLACING_MERGE_TREE_ENGINE) {
            "Owned BI store [${observed.qualifiedName}] must use the $REPLACING_MERGE_TREE_ENGINE engine"
        }
        val expectedInvocation = layout.replacingMergeTreeInvocation(REPLACING_MERGE_TREE_ENGINE)
        check(observed.engineFull.engineInvocation() == expectedInvocation) {
            "Owned BI store [${observed.qualifiedName}] has an unexpected engine definition"
        }
        store.validateKeys(layout)
        store.validateColumns(layout, options.timezone)
    }

    private fun validateCluster(
        options: BiScriptOptions,
        topology: ClickHouseTopology.Cluster,
        store: ClickHouseCatalogObject,
        layout: StoreLayout,
    ) {
        val observed = store.observed
        if (observed.name.endsWith(LOCAL_TABLE_SUFFIX)) {
            check(observed.engine == REPLICATED_REPLACING_MERGE_TREE_ENGINE) {
                "Owned BI store [${observed.qualifiedName}] must use the " +
                    "$REPLICATED_REPLACING_MERGE_TREE_ENGINE engine"
            }
            validateReplicatedStoreEngine(topology, observed, layout)
            store.validateKeys(layout)
        } else {
            check(observed.engine == DISTRIBUTED_ENGINE) {
                "Owned BI store [${observed.qualifiedName}] must use the $DISTRIBUTED_ENGINE engine"
            }
            validateDistributedStoreEngine(topology, observed, layout)
            check(store.partitionKey.isEmpty() && store.sortingKey.isEmpty()) {
                "Owned BI distributed store [${observed.qualifiedName}] must not define local table keys"
            }
        }
        store.validateColumns(layout, options.timezone)
    }

    private fun validateReplicatedStoreEngine(
        topology: ClickHouseTopology.Cluster,
        observed: ObservedBiObject,
        layout: StoreLayout,
    ) {
        val expectedArguments = buildList {
            add(
                ClickHouseSqlSyntax.stringLiteral(
                    "/clickhouse/${topology.installation}/${topology.name}/tables/" +
                        "{shard}/${observed.database}/${observed.name}"
                )
            )
            add(ClickHouseSqlSyntax.stringLiteral("{replica}"))
            layout.versionColumn?.let(::add)
        }
        val actualArguments = observed.engineFull.functionArguments(REPLICATED_REPLACING_MERGE_TREE_ENGINE)
        check(actualArguments == expectedArguments) {
            "Owned BI store [${observed.qualifiedName}] has unexpected replicated engine arguments " +
                "$actualArguments; expected $expectedArguments"
        }
    }

    private fun validateDistributedStoreEngine(
        topology: ClickHouseTopology.Cluster,
        observed: ObservedBiObject,
        layout: StoreLayout,
    ) {
        val expectedArguments = listOf(
            ClickHouseSqlSyntax.stringLiteral(topology.name),
            ClickHouseSqlSyntax.stringLiteral(observed.database),
            ClickHouseSqlSyntax.stringLiteral("${observed.name}$LOCAL_TABLE_SUFFIX"),
            layout.shardingKey,
        )
        val actualArguments = observed.engineFull.functionArguments(DISTRIBUTED_ENGINE)
        check(actualArguments == expectedArguments) {
            "Owned BI store [${observed.qualifiedName}] has an unexpected distributed engine definition"
        }
    }

    private fun ClickHouseCatalogObject.validateKeys(layout: StoreLayout) {
        val name = observed.qualifiedName
        check(partitionKey == layout.partitionKey) {
            "Owned BI store [$name] has an unexpected partition key [$partitionKey]; " +
                "expected [${layout.partitionKey}]"
        }
        check(sortingKey == layout.sortingKey) {
            "Owned BI store [$name] has an unexpected sorting key [$sortingKey]; expected [${layout.sortingKey}]"
        }
    }

    private fun ClickHouseCatalogObject.validateColumns(layout: StoreLayout, timezone: String) {
        val expected = layout.expectedColumns(timezone)
        val actual = columns.map { it.name to it.type }
        check(actual == expected) {
            "Owned BI store [${observed.qualifiedName}] has an unexpected column schema: " +
                "actual=$actual, expected=$expected"
        }
    }

    private enum class StoreLayout(
        private val suffix: String,
        val partitionKey: String,
        val sortingKey: String,
        val versionColumn: String?,
        val shardingKey: String,
    ) {
        COMMAND(
            suffix = "_command_store",
            partitionKey = "toYYYYMM(create_time)",
            sortingKey = "id",
            versionColumn = null,
            shardingKey = "sipHash64(aggregate_id)",
        ),
        STATE(
            suffix = "_state_store",
            partitionKey = "toYYYYMM(create_time)",
            sortingKey = "tenant_id, aggregate_id, version",
            versionColumn = "version",
            shardingKey = "sipHash64(tenant_id, aggregate_id)",
        ),
        STATE_LAST(
            suffix = "_state_last_store",
            partitionKey = "toYYYYMM(first_event_time)",
            sortingKey = "tenant_id, aggregate_id",
            versionColumn = "version",
            shardingKey = "sipHash64(tenant_id, aggregate_id)",
        ),
        ;

        fun replacingMergeTreeInvocation(engine: String): String =
            versionColumn?.let { "$engine($it)" } ?: engine

        fun expectedColumns(timezone: String): List<Pair<String, String>> = when (this) {
            COMMAND -> commandStoreColumns(timezone)
            STATE, STATE_LAST -> stateStoreColumns(timezone)
        }

        companion object {
            fun from(tableName: String): StoreLayout = entries.firstOrNull {
                tableName.length > it.suffix.length && tableName.endsWith(it.suffix)
            } ?: error("Owned BI store [$tableName] has an unsupported store name")
        }
    }
}

private val ObservedBiObject.qualifiedName: String
    get() = "$database.$name"

private fun String.engineInvocation(): String {
    val clauseIndex = listOf(
        " PARTITION BY ",
        " PRIMARY KEY ",
        " ORDER BY ",
        " SAMPLE BY ",
        " TTL ",
        " SETTINGS ",
    ).asSequence()
        .map(::indexOf)
        .filter { it >= 0 }
        .minOrNull()
    return if (clauseIndex == null) trim() else substring(0, clauseIndex).trim()
}

private fun commandStoreColumns(timezone: String): List<Pair<String, String>> = listOf(
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
    "create_time" to dateTime64Type(timezone),
)

private fun stateStoreColumns(timezone: String): List<Pair<String, String>> = listOf(
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
    "first_event_time" to dateTime64Type(timezone),
    "create_time" to dateTime64Type(timezone),
    "tags" to "Map(String, Array(String))",
    "deleted" to "Bool",
)

private fun dateTime64Type(timezone: String): String =
    "DateTime64(3, ${ClickHouseSqlSyntax.stringLiteral(timezone)})"
