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

package me.ahoo.wow.spring.boot.starter.bi

import me.ahoo.wow.api.Wow
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.KafkaOffsetStorage
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = BiScriptProperties.PREFIX)
data class BiScriptProperties(
    val enabled: Boolean = false,
    val database: String? = null,
    val consumerDatabase: String? = null,
    val topology: BiScriptTopologyProperties = BiScriptTopologyProperties(),
    val timezone: String? = null,
    val kafkaBootstrapServers: String? = null,
    val topicPrefix: String? = null,
    val consumerGroupNamespace: String? = null,
    val kafkaOffsetStorage: KafkaOffsetStorage? = null,
    val kafkaKeeperPathPrefix: String? = null,
    val maxExpansionDepth: Int? = null,
    val unsupportedTypeStrategy: UnsupportedTypeStrategy? = null,
) {
    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}bi.script"
    }
}

data class BiScriptTopologyProperties(
    val mode: BiScriptTopologyMode = BiScriptTopologyMode.CLUSTER,
    val cluster: BiScriptClusterProperties? = null,
)

enum class BiScriptTopologyMode {
    CLUSTER,
    STANDALONE,
}

data class BiScriptClusterProperties(
    val name: String? = null,
    val installation: String? = null,
)

internal fun BiScriptProperties.toBiScriptOptions(kafkaProperties: KafkaProperties?): BiScriptOptions {
    return BiScriptOptions(
        database = database ?: defaultBiScriptOptions.database,
        consumerDatabase = consumerDatabase ?: defaultBiScriptOptions.consumerDatabase,
        topology = topology.toTopology(),
        timezone = timezone ?: defaultBiScriptOptions.timezone,
        kafkaBootstrapServers = kafkaBootstrapServers
            ?: kafkaProperties?.bootstrapServersToString()
            ?: defaultBiScriptOptions.kafkaBootstrapServers,
        topicPrefix = topicPrefix
            ?: kafkaProperties?.topicPrefix
            ?: defaultBiScriptOptions.topicPrefix,
        consumerGroupNamespace = consumerGroupNamespace,
        kafkaOffsetStorage = kafkaOffsetStorage ?: defaultBiScriptOptions.kafkaOffsetStorage,
        kafkaKeeperPathPrefix = kafkaKeeperPathPrefix ?: defaultBiScriptOptions.kafkaKeeperPathPrefix,
        maxExpansionDepth = maxExpansionDepth ?: defaultBiScriptOptions.maxExpansionDepth,
        unsupportedTypeStrategy = unsupportedTypeStrategy ?: defaultBiScriptOptions.unsupportedTypeStrategy,
    )
}

private fun BiScriptTopologyProperties.toTopology(): ClickHouseTopology = when (mode) {
    BiScriptTopologyMode.CLUSTER -> ClickHouseTopology.Cluster(
        name = cluster?.name ?: defaultCluster.name,
        installation = cluster?.installation ?: defaultCluster.installation,
    )

    BiScriptTopologyMode.STANDALONE -> {
        require(cluster == null) {
            "topology.cluster must not be configured in STANDALONE mode"
        }
        ClickHouseTopology.Standalone
    }
}

private val defaultBiScriptOptions = BiScriptOptions()
private val defaultCluster = ClickHouseTopology.Cluster()
