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
import org.springframework.boot.context.properties.bind.DefaultValue
import java.net.URI
import java.time.Duration

@ConfigurationProperties(prefix = BiScriptProperties.PREFIX)
data class BiScriptProperties(
    var enabled: Boolean = true,
    var database: String? = null,
    var consumerDatabase: String? = null,
    @NestedConfigurationProperty
    var topology: BiScriptTopologyProperties = BiScriptTopologyProperties(),
    var timezone: String? = null,
    var kafkaBootstrapServers: String? = null,
    var topicPrefix: String? = null,
    var consumerGroupNamespace: String? = null,
    var kafkaOffsetStorage: KafkaOffsetStorage? = null,
    var kafkaKeeperPathPrefix: String? = null,
    var maxExpansionDepth: Int? = null,
    var unsupportedTypeStrategy: UnsupportedTypeStrategy? = null,
    @NestedConfigurationProperty
    var inspector: BiDeploymentInspectorProperties = BiDeploymentInspectorProperties(),
) {
    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}bi.script"
    }
}

data class BiDeploymentInspectorProperties(
    @DefaultValue("NO_OP") var type: BiDeploymentInspectorType = BiDeploymentInspectorType.NO_OP,
    @DefaultValue("30s") var timeout: Duration = Duration.ofSeconds(30),
    @NestedConfigurationProperty
    var clickhouse: BiClickHouseInspectorProperties = BiClickHouseInspectorProperties(),
)

enum class BiDeploymentInspectorType {
    NO_OP,
    CLICKHOUSE,
}

data class BiClickHouseInspectorProperties(
    var endpoints: List<URI> = emptyList(),
    @DefaultValue("default") var username: String = "default",
    @DefaultValue("") var password: String = "",
    @DefaultValue("true") var connectionPoolEnabled: Boolean = true,
    @DefaultValue("3s") var connectionTimeout: Duration = Duration.ofSeconds(3),
    @DefaultValue("10s") var connectionRequestTimeout: Duration = Duration.ofSeconds(10),
    @DefaultValue("10s") var socketTimeout: Duration = Duration.ofSeconds(10),
    @DefaultValue("10s") var executionTimeout: Duration = Duration.ofSeconds(10),
    @DefaultValue("10") var maxConnections: Int = 10,
    @DefaultValue("0") var maxRetries: Int = 0,
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
    @DefaultValue("CLUSTER") var mode: BiScriptTopologyMode = BiScriptTopologyMode.CLUSTER,
    @NestedConfigurationProperty
    var cluster: BiScriptClusterProperties? = null,
)

enum class BiScriptTopologyMode {
    CLUSTER,
    STANDALONE,
}

data class BiScriptClusterProperties(
    var name: String? = null,
    var installation: String? = null,
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
