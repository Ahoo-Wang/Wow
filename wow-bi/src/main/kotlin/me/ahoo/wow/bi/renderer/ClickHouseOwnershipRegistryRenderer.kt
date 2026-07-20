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

import me.ahoo.wow.bi.BiOwnershipRegistry
import me.ahoo.wow.bi.BiOwnershipRegistryEntry
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.rowFingerprint
import me.ahoo.wow.bi.snapshotFingerprint

/**
 * Renders the durable ownership registry used to avoid catalog-wide discovery.
 *
 * Registry rows are append-only revisions. ReplacingMergeTree makes retries idempotent while retaining enough state
 * for an interrupted create/drop to be resumed from PENDING_CREATE/PENDING_DROP instead of guessing from comments.
 */
internal class ClickHouseOwnershipRegistryRenderer(
    private val options: BiScriptOptions,
    private val deploymentId: String,
) {
    init {
        require(DEPLOYMENT_ID_PATTERN.matches(deploymentId)) {
            "Invalid BI registry deploymentId: $deploymentId"
        }
    }

    fun renderCreateStatements(registryName: String): List<String> {
        val comment = literal("$REGISTRY_COMMENT_PREFIX$deploymentId")
        val create = """
            CREATE TABLE IF NOT EXISTS ${qualified(options.consumerDatabase, registryName)}${scopeClause()}
            (
                ${identifier("deployment_id")} FixedString(32),
                ${identifier("row_kind")} LowCardinality(String),
                ${identifier("object_database")} String,
                ${identifier("object_name")} String,
                ${identifier("kind")} LowCardinality(String),
                ${identifier("aggregate")} Nullable(String),
                ${identifier("consumer_identity")} Nullable(FixedString(32)),
                ${identifier("definition_fingerprint")} FixedString(32),
                ${identifier("revision")} UInt64,
                ${identifier("status")} LowCardinality(String),
                ${identifier("row_fingerprint")} FixedString(32),
                ${identifier("recorded_at")} DateTime64(3, 'UTC') DEFAULT now64(3)
            ) ${registryEngine()}
              ORDER BY (${identifier("deployment_id")}, ${identifier("row_kind")},
                        ${identifier("object_database")},
                        ${identifier("object_name")})
              COMMENT $comment;
        """.trimIndent()
        return immutableStatements(create)
    }

    fun renderSnapshotStatement(registry: BiOwnershipRegistry): String {
        require(registry.deploymentId == deploymentId) {
            "BI ownership registry deploymentId does not match its renderer"
        }
        val values = (
            listOf(registry.renderHeadValues()) +
                registry.entries.map { entry -> entry.renderValues() }
            ).joinToString(",\n")
        return """
            INSERT INTO ${qualified(options.consumerDatabase, registry.name)}
            (${identifier("deployment_id")}, ${identifier("row_kind")},
             ${identifier("object_database")}, ${identifier("object_name")}, ${identifier("kind")},
             ${identifier("aggregate")}, ${identifier("consumer_identity")},
             ${identifier("definition_fingerprint")}, ${identifier("revision")}, ${identifier("status")},
             ${identifier("row_fingerprint")})
            VALUES
            $values;
        """.trimIndent()
    }

    fun renderDropStatement(registryName: String): String =
        "DROP TABLE IF EXISTS ${qualified(options.consumerDatabase, registryName)}${scopeClause()};"

    private fun BiOwnershipRegistry.renderHeadValues(): String = listOf(
        literal(deploymentId),
        literal(HEAD_ROW_KIND),
        literal(""),
        literal(""),
        literal("ANCHOR"),
        "NULL",
        "NULL",
        literal(snapshotFingerprint()),
        revision.toString(),
        literal("ACTIVE"),
        literal(snapshotFingerprint()),
    ).joinToString(prefix = "(", postfix = ")")

    private fun BiOwnershipRegistryEntry.renderValues(): String = listOf(
        literal(deploymentId),
        literal(OBJECT_ROW_KIND),
        literal(key.database),
        literal(key.name),
        literal(kind.name),
        aggregate?.let(::literal) ?: "NULL",
        consumerIdentity?.let(::literal) ?: "NULL",
        literal(definitionFingerprint),
        revision.toString(),
        literal(status.name),
        literal(rowFingerprint()),
    ).joinToString(prefix = "(", postfix = ")")

    private fun scopeClause(): String =
        (options.topology as? ClickHouseTopology.Cluster)?.let { cluster ->
            " ON CLUSTER ${literal(cluster.name)}"
        }.orEmpty()

    private fun registryEngine(): String = when (val configuredTopology = options.topology) {
        ClickHouseTopology.Standalone -> "ENGINE = ReplacingMergeTree(${identifier("revision")})"
        is ClickHouseTopology.Cluster -> {
            val path = "/clickhouse/${configuredTopology.installation}/${configuredTopology.name}/" +
                "control/wow-bi/$deploymentId"
            "ENGINE = ReplicatedReplacingMergeTree(${literal(path)}, " +
                "${literal("{shard}-{replica}")}, ${identifier("revision")})"
        }
    }

    companion object {
        internal const val REGISTRY_COMMENT_PREFIX: String = "wow-bi-registry:"
        internal const val HEAD_ROW_KIND: String = "HEAD"
        internal const val OBJECT_ROW_KIND: String = "OBJECT"
        private val DEPLOYMENT_ID_PATTERN = Regex("[0-9a-f]{32}")
    }
}
