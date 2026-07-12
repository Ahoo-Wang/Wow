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

import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.openapi.contract.bi.BiScriptRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptTopologyMode
import me.ahoo.wow.openapi.contract.bi.BiScriptTopologyRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptUnsupportedTypeStrategy

internal fun BiScriptRequest.toBiScriptOptions(base: BiScriptOptions): BiScriptOptions =
    BiScriptOptions(
        database = database ?: base.database,
        consumerDatabase = consumerDatabase ?: base.consumerDatabase,
        topology = topology?.toTopology(base.topology) ?: base.topology,
        timezone = timezone ?: base.timezone,
        kafkaBootstrapServers = kafkaBootstrapServers ?: base.kafkaBootstrapServers,
        topicPrefix = topicPrefix ?: base.topicPrefix,
        maxExpansionDepth = maxExpansionDepth ?: base.maxExpansionDepth,
        unsupportedTypeStrategy = unsupportedTypeStrategy?.toDomain()
            ?: base.unsupportedTypeStrategy,
    )

private fun BiScriptTopologyRequest.toTopology(base: ClickHouseTopology): ClickHouseTopology {
    return when (mode) {
        BiScriptTopologyMode.CLUSTER -> {
            val baseCluster = base as? ClickHouseTopology.Cluster ?: ClickHouseTopology.Cluster()
            ClickHouseTopology.Cluster(
                name = cluster?.name ?: baseCluster.name,
                installation = cluster?.installation ?: baseCluster.installation,
                shard = cluster?.shard ?: baseCluster.shard,
                replica = cluster?.replica ?: baseCluster.replica,
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
