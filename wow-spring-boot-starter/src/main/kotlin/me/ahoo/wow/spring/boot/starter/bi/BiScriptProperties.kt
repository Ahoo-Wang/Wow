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
import me.ahoo.wow.bi.ClickHouseClientOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.KafkaOffsetStorage
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.net.URI
import java.time.Duration

@ConfigurationProperties(prefix = BiScriptProperties.PREFIX)
data class BiScriptProperties(
    val enabled: Boolean = true,
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
    @NestedConfigurationProperty
    val inspector: BiDeploymentInspectorProperties = BiDeploymentInspectorProperties(),
) {
    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}bi.script"
    }
}

data class BiDeploymentInspectorProperties(
    var type: BiDeploymentInspectorType = BiDeploymentInspectorType.NO_OP,
    var timeout: Duration = Duration.ofSeconds(30),
    @NestedConfigurationProperty
    var clickhouse: BiClickHouseInspectorProperties = BiClickHouseInspectorProperties(),
)

enum class BiDeploymentInspectorType {
    NO_OP,
    CLICKHOUSE,
}

data class BiClickHouseInspectorProperties(
    var endpoints: List<URI> = emptyList(),
    var username: String = "default",
    var password: String = "",
    var connectionPoolEnabled: Boolean = true,
    var connectionTimeout: Duration = Duration.ofSeconds(3),
    var connectionRequestTimeout: Duration = Duration.ofSeconds(10),
    var socketTimeout: Duration = Duration.ofSeconds(10),
    var executionTimeout: Duration = Duration.ofSeconds(10),
    var maxConnections: Int = 10,
    var maxRetries: Int = 0,
) {
    override fun toString(): String =
        "BiClickHouseInspectorProperties(" +
            "endpoints=$endpoints, " +
            "username=$username, " +
            "password=******, " +
            "connectionPoolEnabled=$connectionPoolEnabled, " +
            "connectionTimeout=$connectionTimeout, " +
            "connectionRequestTimeout=$connectionRequestTimeout, " +
            "socketTimeout=$socketTimeout, " +
            "executionTimeout=$executionTimeout, " +
            "maxConnections=$maxConnections, " +
            "maxRetries=$maxRetries)"
}

internal fun BiClickHouseInspectorProperties.toClientOptions(): ClickHouseClientOptions = ClickHouseClientOptions(
    endpoints = endpoints,
    username = username,
    password = password,
    connectionPoolEnabled = connectionPoolEnabled,
    connectionTimeout = connectionTimeout,
    connectionRequestTimeout = connectionRequestTimeout,
    socketTimeout = socketTimeout,
    executionTimeout = executionTimeout,
    maxConnections = maxConnections,
    maxRetries = maxRetries,
)

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
