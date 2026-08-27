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

import me.ahoo.wow.bi.BiConsumerIdentity
import me.ahoo.wow.bi.BiDeploymentDescriptor
import me.ahoo.wow.bi.BiDeploymentPhase
import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiObjectKind
import me.ahoo.wow.bi.BiObjectMetadata
import me.ahoo.wow.bi.BiObjectMetadataCodec
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.KafkaOffsetStorage
import me.ahoo.wow.bi.expansion.BiTableNaming
import me.ahoo.wow.bi.expansion.plan.ColumnExtraction
import me.ahoo.wow.bi.expansion.plan.ColumnPlacement
import me.ahoo.wow.bi.expansion.plan.ColumnPlan
import me.ahoo.wow.bi.expansion.plan.ColumnReference
import me.ahoo.wow.bi.type.ClickHouseType

internal class ClickHouseRenderContext(
    val options: BiScriptOptions,
    val consumerIdentity: BiConsumerIdentity,
    val deployment: BiDeploymentDescriptor,
    val catalogMutationMode: CatalogMutationMode,
    private val retainedQueueKeys: Set<BiObjectKey>,
) {
    val naming = BiTableNaming(options)
    val topology = options.topology.toDdl()
    val metadataColumns = buildMetadataColumns(options.timezone)

    fun consumerGroup(consumerTable: String): String =
        "wow-bi.${consumerIdentity.value}.$consumerTable"

    fun kafkaSettings(queueTable: String, comment: String): String {
        val settings = buildList {
            if (options.kafkaOffsetStorage == KafkaOffsetStorage.KEEPER) {
                val keeperPath = "${options.kafkaKeeperPathPrefix.trimEnd('/')}/" +
                    "${consumerIdentity.value}/$queueTable"
                add("kafka_keeper_path = ${literal(keeperPath)}")
                val replicaName = when (options.topology) {
                    is ClickHouseTopology.Cluster -> "{replica}"
                    ClickHouseTopology.Standalone -> consumerIdentity.value
                }
                add("kafka_replica_name = ${literal(replicaName)}")
            }
        }
        val engineSettings = settings.takeIf { it.isNotEmpty() }
            ?.let { "SETTINGS ${it.joinToString(",\n                         ")}" }
            .orEmpty()
        val querySettings = if (options.kafkaOffsetStorage == KafkaOffsetStorage.KEEPER) {
            "\nSETTINGS allow_experimental_kafka_offsets_storage_in_keeper = 1"
        } else {
            ""
        }
        return listOf(engineSettings, "COMMENT $comment", querySettings)
            .filter(String::isNotBlank)
            .joinToString("\n")
    }

    fun metadataComment(
        kind: BiObjectKind,
        aggregate: String?,
        phase: BiDeploymentPhase = BiDeploymentPhase.STABLE,
        registryRevision: Long? = null,
    ): String = literal(
        BiObjectMetadataCodec.encode(
            BiObjectMetadata(
                deploymentId = deployment.deploymentId,
                configurationFingerprint = deployment.configurationFingerprint,
                topologyFingerprint = deployment.topologyFingerprint,
                phase = phase,
                aggregate = aggregate,
                kind = kind,
                consumerIdentity = consumerIdentity.value,
                registryRevision = if (kind == BiObjectKind.ANCHOR) registryRevision else null,
            )
        )
    )

    fun isQueueRetained(queueTable: String): Boolean =
        BiObjectKey(options.consumerDatabase, queueTable) in retainedQueueKeys

    fun epochMillis(source: String, property: String): String =
        "toDateTime64(${jsonInt(source, property)} / 1000.0, 3, ${literal(options.timezone)})"
}

private fun buildMetadataColumns(timezone: String): List<ColumnPlan> = listOf(
    metadataColumn("id", ClickHouseType.String),
    metadataColumn("aggregate_id", ClickHouseType.String),
    metadataColumn("tenant_id", ClickHouseType.String),
    metadataColumn("owner_id", ClickHouseType.String),
    metadataColumn("space_id", ClickHouseType.String),
    metadataColumn("command_id", ClickHouseType.String),
    metadataColumn("request_id", ClickHouseType.String),
    metadataColumn("version", ClickHouseType.UInt32),
    metadataColumn("first_operator", ClickHouseType.String),
    metadataColumn("first_event_time", ClickHouseType.DateTime64(3, timezone)),
    metadataColumn("create_time", ClickHouseType.DateTime64(3, timezone)),
    metadataColumn(
        "tags",
        ClickHouseType.Map(
            ClickHouseType.String,
            ClickHouseType.Array(ClickHouseType.String),
        ),
    ),
    metadataColumn("deleted", ClickHouseType.Bool),
)

private fun metadataColumn(name: String, type: ClickHouseType): ColumnPlan = ColumnPlan(
    name = name,
    path = name,
    targetName = "__$name",
    type = type,
    extraction = ColumnExtraction.Reference(ColumnReference.Input(name)),
    placement = ColumnPlacement.SELECT,
)
