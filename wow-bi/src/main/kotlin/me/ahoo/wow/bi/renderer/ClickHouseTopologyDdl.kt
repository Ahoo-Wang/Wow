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

package me.ahoo.wow.bi.renderer

import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax.quoteIdentifier
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax.stringLiteral

internal data class ReplacingMergeTreeSpec(val versionColumn: String?)

internal interface ClickHouseTopologyDdl {
    val scopeClause: String

    fun physicalTableName(logicalTableName: String): String

    fun engineSql(spec: ReplacingMergeTreeSpec): String

    fun distributedFacade(
        database: String,
        logicalTableName: String,
        physicalTableName: String,
        shardingKey: String,
    ): String?

    fun dropTableNames(logicalTableName: String): List<String>
}

internal fun ClickHouseTopology.toDdl(): ClickHouseTopologyDdl = when (this) {
    is ClickHouseTopology.Cluster -> ClusterTopologyDdl(this)
    ClickHouseTopology.Standalone -> StandaloneTopologyDdl
}

private class ClusterTopologyDdl(private val topology: ClickHouseTopology.Cluster) : ClickHouseTopologyDdl {
    override val scopeClause: String = "ON CLUSTER ${stringLiteral(topology.name)}"

    override fun physicalTableName(logicalTableName: String): String = "${logicalTableName}_local"

    override fun engineSql(spec: ReplacingMergeTreeSpec): String {
        val path = stringLiteral(
            "/clickhouse/${topology.installation}/${topology.name}/tables/" +
                "{shard}/{database}/{table}"
        )
        val replica = stringLiteral("{replica}")
        return spec.versionColumn?.let { versionColumn ->
            "ENGINE = ReplicatedReplacingMergeTree($path, $replica, " +
                "${quoteIdentifier(versionColumn)})"
        } ?: "ENGINE = ReplicatedReplacingMergeTree($path, $replica)"
    }

    override fun distributedFacade(
        database: String,
        logicalTableName: String,
        physicalTableName: String,
        shardingKey: String,
    ): String =
        """
            CREATE TABLE IF NOT EXISTS ${qualified(database, logicalTableName)} $scopeClause
            AS ${qualified(database, physicalTableName)}
            ENGINE = Distributed(${stringLiteral(topology.name)}, ${quoteIdentifier(database)},
                                 ${stringLiteral(physicalTableName)}, $shardingKey);
        """.trimIndent()

    override fun dropTableNames(logicalTableName: String): List<String> =
        listOf(logicalTableName, physicalTableName(logicalTableName))
}

private data object StandaloneTopologyDdl : ClickHouseTopologyDdl {
    override val scopeClause: String = ""

    override fun physicalTableName(logicalTableName: String): String = logicalTableName

    override fun engineSql(spec: ReplacingMergeTreeSpec): String =
        spec.versionColumn?.let { versionColumn ->
            "ENGINE = ReplacingMergeTree(${quoteIdentifier(versionColumn)})"
        } ?: "ENGINE = ReplacingMergeTree"

    override fun distributedFacade(
        database: String,
        logicalTableName: String,
        physicalTableName: String,
        shardingKey: String,
    ): String? = null

    override fun dropTableNames(logicalTableName: String): List<String> = listOf(logicalTableName)
}

private fun qualified(database: String, table: String): String =
    "${quoteIdentifier(database)}.${quoteIdentifier(table)}"
