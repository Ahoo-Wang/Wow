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

import me.ahoo.wow.bi.renderer.ClickHouseOwnershipRegistryRenderer
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax

internal object ClickHouseOwnershipRegistryShapeValidator {
    fun validate(
        topology: ClickHouseTopology,
        deploymentId: String,
        table: ClickHouseRegistryTableDefinition,
        columns: List<ClickHouseCatalogColumn>,
    ) {
        val expectedEngine = when (topology) {
            ClickHouseTopology.Standalone -> STANDALONE_ENGINE
            is ClickHouseTopology.Cluster -> CLUSTER_ENGINE
        }
        check(table.engine == expectedEngine) {
            "ClickHouse BI ownership registry [${table.key.qualifiedName}] must use the $expectedEngine engine"
        }
        check(table.engineFull.functionArguments(expectedEngine) == expectedEngineArguments(topology, deploymentId)) {
            "ClickHouse BI ownership registry [${table.key.qualifiedName}] has an unexpected engine definition"
        }
        check(table.comment == "${ClickHouseOwnershipRegistryRenderer.REGISTRY_COMMENT_PREFIX}$deploymentId") {
            "ClickHouse BI ownership registry [${table.key.qualifiedName}] has an unexpected ownership comment"
        }
        check(table.sortingKey.normalizedKey() == EXPECTED_SORTING_KEY) {
            "ClickHouse BI ownership registry [${table.key.qualifiedName}] has an unexpected sorting key"
        }
        val actualColumns = columns.sortedBy(ClickHouseCatalogColumn::position).map { column ->
            column.name to column.type
        }
        check(actualColumns == EXPECTED_COLUMNS) {
            "ClickHouse BI ownership registry [${table.key.qualifiedName}] has an unexpected column schema: " +
                "actual=$actualColumns, expected=$EXPECTED_COLUMNS"
        }
    }

    private fun expectedEngineArguments(
        topology: ClickHouseTopology,
        deploymentId: String,
    ): List<String> = when (topology) {
        ClickHouseTopology.Standalone -> listOf(REVISION_COLUMN)
        is ClickHouseTopology.Cluster -> listOf(
            ClickHouseSqlSyntax.stringLiteral(
                "/clickhouse/${topology.installation}/${topology.name}/control/wow-bi/$deploymentId"
            ),
            ClickHouseSqlSyntax.stringLiteral("{shard}-{replica}"),
            REVISION_COLUMN,
        )
    }

    private fun String.normalizedKey(): String =
        trim().removeSurrounding("(", ")").replace(" ", "")

    private val BiObjectKey.qualifiedName: String
        get() = "$database.$name"

    private const val STANDALONE_ENGINE = "ReplacingMergeTree"
    private const val CLUSTER_ENGINE = "ReplicatedReplacingMergeTree"
    private const val REVISION_COLUMN = "revision"
    private const val EXPECTED_SORTING_KEY =
        "deployment_id,row_kind,object_database,object_name"
    private val EXPECTED_COLUMNS = listOf(
        "deployment_id" to "FixedString(32)",
        "row_kind" to "LowCardinality(String)",
        "object_database" to "String",
        "object_name" to "String",
        "kind" to "LowCardinality(String)",
        "aggregate" to "Nullable(String)",
        "consumer_identity" to "Nullable(FixedString(32))",
        "definition_fingerprint" to "FixedString(32)",
        "revision" to "UInt64",
        "status" to "LowCardinality(String)",
        "row_fingerprint" to "FixedString(32)",
        "recorded_at" to "DateTime64(3, 'UTC')",
    )
}
