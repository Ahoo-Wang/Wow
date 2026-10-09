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

package me.ahoo.wow.bi.catalog

import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.ObservedBiObject
import me.ahoo.wow.bi.layout.BiEngine
import me.ahoo.wow.bi.layout.BiLayout
import me.ahoo.wow.bi.layout.BiStoreSchema
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax
import me.ahoo.wow.bi.renderer.storeDateTimeType

/** Compares an owned store in the catalog with its [BiStoreSchema]. */
internal object ClickHouseStoreShapeValidator {
    fun validate(options: BiScriptOptions, store: ClickHouseCatalogObject) {
        val observed = store.observed
        val logicalName = when (options.topology) {
            ClickHouseTopology.Standalone -> observed.name
            is ClickHouseTopology.Cluster -> BiLayout.logicalStore(observed.name)
        }
        val layout = checkNotNull(BiStoreSchema.ofStore(logicalName)) {
            "Owned BI store [${observed.qualifiedName}] has an unsupported store name"
        }
        when (val topology = options.topology) {
            ClickHouseTopology.Standalone -> validateStandalone(options, store, layout)
            is ClickHouseTopology.Cluster -> validateCluster(options, topology, store, layout)
        }
    }

    private fun validateStandalone(
        options: BiScriptOptions,
        store: ClickHouseCatalogObject,
        layout: BiStoreSchema,
    ) {
        val observed = store.observed
        check(observed.engine == BiEngine.REPLACING_MERGE_TREE) {
            "Owned BI store [${observed.qualifiedName}] must use the ${BiEngine.REPLACING_MERGE_TREE} engine"
        }
        val expectedInvocation = layout.versionColumn?.let {
            "${BiEngine.REPLACING_MERGE_TREE}($it)"
        } ?: BiEngine.REPLACING_MERGE_TREE
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
        layout: BiStoreSchema,
    ) {
        val observed = store.observed
        if (BiLayout.isLocalStore(observed.name)) {
            check(observed.engine == BiEngine.REPLICATED_REPLACING_MERGE_TREE) {
                "Owned BI store [${observed.qualifiedName}] must use the " +
                    "${BiEngine.REPLICATED_REPLACING_MERGE_TREE} engine"
            }
            validateReplicatedStoreEngine(topology, observed, layout)
            store.validateKeys(layout)
        } else {
            check(observed.engine == BiEngine.DISTRIBUTED) {
                "Owned BI store [${observed.qualifiedName}] must use the ${BiEngine.DISTRIBUTED} engine"
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
        layout: BiStoreSchema,
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
        val actualArguments = observed.engineFull.functionArguments(BiEngine.REPLICATED_REPLACING_MERGE_TREE)
        check(actualArguments == expectedArguments) {
            "Owned BI store [${observed.qualifiedName}] has unexpected replicated engine arguments " +
                "$actualArguments; expected $expectedArguments"
        }
    }

    private fun validateDistributedStoreEngine(
        topology: ClickHouseTopology.Cluster,
        observed: ObservedBiObject,
        layout: BiStoreSchema,
    ) {
        val expectedArguments = listOf(
            ClickHouseSqlSyntax.stringLiteral(topology.name),
            ClickHouseSqlSyntax.stringLiteral(observed.database),
            ClickHouseSqlSyntax.stringLiteral(BiLayout.localStore(observed.name)),
            layout.catalogShardingKey,
        )
        val actualArguments = observed.engineFull.functionArguments(BiEngine.DISTRIBUTED)
        check(actualArguments == expectedArguments) {
            "Owned BI store [${observed.qualifiedName}] has an unexpected distributed engine definition"
        }
    }

    private fun ClickHouseCatalogObject.validateKeys(layout: BiStoreSchema) {
        val name = observed.qualifiedName
        check(partitionKey == layout.catalogPartitionKey) {
            "Owned BI store [$name] has an unexpected partition key [$partitionKey]; " +
                "expected [${layout.catalogPartitionKey}]"
        }
        check(sortingKey == layout.catalogSortingKey) {
            "Owned BI store [$name] has an unexpected sorting key [$sortingKey]; expected [${layout.catalogSortingKey}]"
        }
    }

    private fun ClickHouseCatalogObject.validateColumns(layout: BiStoreSchema, timezone: String) {
        val expected = layout.columns(storeDateTimeType(timezone)).map { it.name to it.type }
        val actual = columns.map { it.name to it.type }
        check(actual == expected) {
            "Owned BI store [${observed.qualifiedName}] has an unexpected column schema: " +
                "actual=$actual, expected=$expected"
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
