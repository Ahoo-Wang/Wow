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
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.boot.context.properties.bind.DefaultValue
import java.net.URI
import java.time.Duration

@ConfigurationProperties(prefix = BiScriptProperties.PREFIX)
data class BiScriptProperties @ConstructorBinding constructor(
    @DefaultValue("true") val enabled: Boolean,
    val database: String? = null,
    val consumerDatabase: String? = null,
    @NestedConfigurationProperty
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
    constructor() : this(enabled = true)

    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}bi.script"
    }
}

data class BiDeploymentInspectorProperties @ConstructorBinding constructor(
    @DefaultValue("NO_OP") val type: BiDeploymentInspectorType,
    @DefaultValue("30s") val timeout: Duration = Duration.ofSeconds(30),
    @NestedConfigurationProperty
    val clickhouse: BiClickHouseInspectorProperties = BiClickHouseInspectorProperties(),
) {
    constructor() : this(type = BiDeploymentInspectorType.NO_OP)
}

enum class BiDeploymentInspectorType {
    NO_OP,
    CLICKHOUSE,
}

data class BiClickHouseInspectorProperties @ConstructorBinding constructor(
    val endpoints: List<URI> = emptyList(),
    @DefaultValue("default") val username: String,
    @DefaultValue("") val password: String = "",
    @DefaultValue("true") val connectionPoolEnabled: Boolean = true,
    @DefaultValue("3s") val connectionTimeout: Duration = Duration.ofSeconds(3),
    @DefaultValue("10s") val connectionRequestTimeout: Duration = Duration.ofSeconds(10),
    @DefaultValue("10s") val socketTimeout: Duration = Duration.ofSeconds(10),
    @DefaultValue("10s") val executionTimeout: Duration = Duration.ofSeconds(10),
    @DefaultValue("10") val maxConnections: Int = 10,
    @DefaultValue("0") val maxRetries: Int = 0,
) {
    constructor() : this(username = "default")

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

data class BiScriptTopologyProperties @ConstructorBinding constructor(
    @DefaultValue("CLUSTER") val mode: BiScriptTopologyMode,
    @NestedConfigurationProperty
    val cluster: BiScriptClusterProperties? = null,
) {
    constructor() : this(mode = BiScriptTopologyMode.CLUSTER)
}

enum class BiScriptTopologyMode {
    CLUSTER,
    STANDALONE,
}

data class BiScriptClusterProperties @ConstructorBinding constructor(
    val name: String?,
    val installation: String? = null,
) {
    constructor() : this(name = null)
}

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
