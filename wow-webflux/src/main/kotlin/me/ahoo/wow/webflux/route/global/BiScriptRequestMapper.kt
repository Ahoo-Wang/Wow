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

package me.ahoo.wow.webflux.route.global

import me.ahoo.wow.bi.BiAggregateManifest
import me.ahoo.wow.bi.BiDeploymentManifest
import me.ahoo.wow.bi.BiManifestTopology
import me.ahoo.wow.bi.BiScriptManifest
import me.ahoo.wow.bi.BiScriptOperation
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.KafkaOffsetStorage
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.openapi.contract.bi.BiScriptKafkaOffsetStorage
import me.ahoo.wow.openapi.contract.bi.BiScriptOperationMode
import me.ahoo.wow.openapi.contract.bi.BiScriptRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptTopologyMode
import me.ahoo.wow.openapi.contract.bi.BiScriptTopologyRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptUnsupportedTypeStrategy

internal fun BiScriptRequest.toBiScriptOptions(base: BiScriptOptions): BiScriptOptions {
    maxExpansionDepth?.let { requestedDepth ->
        require(requestedDepth <= base.maxExpansionDepth) {
            "maxExpansionDepth must be less than or equal to the configured maximum of ${base.maxExpansionDepth}"
        }
    }
    return BiScriptOptions(
        database = database ?: base.database,
        consumerDatabase = consumerDatabase ?: base.consumerDatabase,
        topology = topology?.toTopology(base.topology) ?: base.topology,
        timezone = timezone ?: base.timezone,
        kafkaBootstrapServers = kafkaBootstrapServers ?: base.kafkaBootstrapServers,
        topicPrefix = topicPrefix ?: base.topicPrefix,
        consumerGroupNamespace = base.consumerGroupNamespace,
        kafkaOffsetStorage = base.kafkaOffsetStorage,
        kafkaKeeperPathPrefix = base.kafkaKeeperPathPrefix,
        maxExpansionDepth = maxExpansionDepth ?: base.maxExpansionDepth,
        unsupportedTypeStrategy = unsupportedTypeStrategy?.toDomain()
            ?: base.unsupportedTypeStrategy,
    )
}

internal fun BiScriptRequest.toBiScriptOperation(): BiScriptOperation = when (operation) {
    BiScriptOperationMode.DEPLOY -> {
        require(replayFromEarliestConfirmed == null) {
            "replayFromEarliestConfirmed is only valid for RESET"
        }
        BiScriptOperation.Deploy(previousManifest?.toDomain())
    }

    BiScriptOperationMode.RESET -> {
        BiScriptOperation.Reset(
            previousManifest = requireNotNull(previousManifest) {
                "previousManifest is required for RESET"
            }.toDomain(),
            replayFromEarliestConfirmed = requireNotNull(replayFromEarliestConfirmed) {
                "replayFromEarliestConfirmed is required for RESET"
            },
        )
    }
}

private fun me.ahoo.wow.openapi.contract.bi.BiScriptManifestContract.toDomain(): BiScriptManifest =
    BiScriptManifest(
        formatVersion = formatVersion,
        layoutVersion = layoutVersion,
        deployment = BiDeploymentManifest(
            database = deployment.database,
            consumerDatabase = deployment.consumerDatabase,
            topology = when (deployment.topology.mode) {
                BiScriptTopologyMode.CLUSTER -> BiManifestTopology.CLUSTER
                BiScriptTopologyMode.STANDALONE -> BiManifestTopology.STANDALONE
            },
            clusterName = deployment.topology.cluster?.name,
            installation = deployment.topology.cluster?.installation,
            timezone = deployment.timezone,
            kafkaBootstrapServers = deployment.kafkaBootstrapServers,
            topicPrefix = deployment.topicPrefix,
            consumerGroupNamespace = deployment.consumerGroupNamespace,
            kafkaOffsetStorage = when (deployment.kafkaOffsetStorage) {
                BiScriptKafkaOffsetStorage.BROKER -> KafkaOffsetStorage.BROKER
                BiScriptKafkaOffsetStorage.KEEPER -> KafkaOffsetStorage.KEEPER
            },
            kafkaKeeperPathPrefix = deployment.kafkaKeeperPathPrefix,
        ),
        consumerGeneration = consumerGeneration,
        aggregates = aggregates.map { aggregate ->
            BiAggregateManifest(
                aggregate = aggregate.aggregate,
                tablePrefix = aggregate.tablePrefix,
                expansionViews = aggregate.expansionViews,
            )
        },
        retainedAggregates = retainedAggregates.map { aggregate ->
            BiAggregateManifest(
                aggregate = aggregate.aggregate,
                tablePrefix = aggregate.tablePrefix,
                expansionViews = aggregate.expansionViews,
            )
        },
    )

private fun BiScriptTopologyRequest.toTopology(base: ClickHouseTopology): ClickHouseTopology {
    return when (mode) {
        BiScriptTopologyMode.CLUSTER -> {
            val baseCluster = base as? ClickHouseTopology.Cluster ?: ClickHouseTopology.Cluster()
            ClickHouseTopology.Cluster(
                name = cluster?.name ?: baseCluster.name,
                installation = cluster?.installation ?: baseCluster.installation,
            )
        }

        BiScriptTopologyMode.STANDALONE -> {
            require(cluster == null) {
                "topology.cluster must not be configured in STANDALONE mode"
            }
            ClickHouseTopology.Standalone
        }
    }
}

private fun BiScriptUnsupportedTypeStrategy.toDomain(): UnsupportedTypeStrategy = when (this) {
    BiScriptUnsupportedTypeStrategy.FAIL -> UnsupportedTypeStrategy.FAIL
    BiScriptUnsupportedTypeStrategy.RAW_JSON -> UnsupportedTypeStrategy.RAW_JSON
}
