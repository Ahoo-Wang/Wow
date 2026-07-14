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

import com.clickhouse.client.api.ClientException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.sql.DriverManager
import java.time.Duration

class ClickHouseBiDeploymentInspectorIntegrationTest {
    @Test
    fun `should apply execution timeout to a real ClickHouse request`() {
        ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE)).use { clickHouse ->
            clickHouse.start()
            NativeClickHouseCatalogClient.create(
                ClickHouseClientOptions(
                    endpoints = listOf(URI.create(clickHouse.httpUrl)),
                    username = clickHouse.username,
                    password = clickHouse.password,
                    connectionTimeout = Duration.ofSeconds(5),
                    socketTimeout = Duration.ofSeconds(2),
                    executionTimeout = Duration.ofMillis(100),
                )
            ).use { catalogClient ->
                assertThrownBy<ClientException> {
                    catalogClient.query(
                        sql = "SELECT sleep(1) AS value",
                        parameters = emptyMap(),
                        columns = listOf("value"),
                    )
                }.hasMessageContaining("ClickHouse BI catalog query timed out")
            }
        }
    }

    @Test
    fun `should inspect the real ClickHouse system catalog through client v2`() {
        ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE)).use { clickHouse ->
            clickHouse.start()
            val options = BiScriptOptions(
                database = DATABASE,
                consumerDatabase = CONSUMER_DATABASE,
                consumerGroupNamespace = "native-inspector-integration",
                topology = ClickHouseTopology.Standalone,
                kafkaBootstrapServers = KAFKA_BOOTSTRAP_SERVERS,
            )
            val descriptor = BiDeploymentDescriptor.from(options)
            val identity = BiConsumerIdentity.deterministic(descriptor)
            val storeMetadata = BiObjectMetadata(
                deploymentId = descriptor.deploymentId,
                configurationFingerprint = descriptor.configurationFingerprint,
                topologyFingerprint = descriptor.topologyFingerprint,
                aggregate = "integration.catalog",
                kind = BiObjectKind.STORE,
            )
            val queueMetadata = BiObjectMetadata(
                deploymentId = descriptor.deploymentId,
                configurationFingerprint = descriptor.configurationFingerprint,
                topologyFingerprint = descriptor.topologyFingerprint,
                aggregate = "integration.catalog",
                kind = BiObjectKind.QUEUE,
                consumerIdentity = identity.value,
            )
            val anchorMetadata = BiObjectMetadata(
                deploymentId = descriptor.deploymentId,
                configurationFingerprint = descriptor.configurationFingerprint,
                topologyFingerprint = descriptor.topologyFingerprint,
                kind = BiObjectKind.ANCHOR,
                consumerIdentity = identity.value,
            )
            val expectedGroup = "wow-bi.${identity.value}.catalog_command_consumer"
            DriverManager.getConnection(clickHouse.jdbcUrl, clickHouse.username, clickHouse.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE DATABASE $DATABASE")
                    statement.execute("CREATE DATABASE $CONSUMER_DATABASE")
                    statement.execute(
                        "CREATE TABLE $DATABASE.$TABLE (id UInt64) " +
                            "ENGINE = ReplacingMergeTree ORDER BY id " +
                            "COMMENT '${BiObjectMetadataCodec.encode(storeMetadata)}'"
                    )
                    statement.execute(
                        "CREATE TABLE $CONSUMER_DATABASE.$QUEUE (data String) " +
                            "ENGINE = Kafka('$KAFKA_BOOTSTRAP_SERVERS', '${options.topicPrefix}integration.catalog.command', " +
                            "'$expectedGroup', 'JSONAsString') " +
                            "SETTINGS kafka_num_consumers = 2 " +
                            "COMMENT '${BiObjectMetadataCodec.encode(queueMetadata)}'"
                    )
                    statement.execute(
                        "CREATE VIEW $CONSUMER_DATABASE.__wow_bi_deployment AS " +
                            "SELECT 1 AS alive WHERE 0 " +
                            "COMMENT '${BiObjectMetadataCodec.encode(anchorMetadata)}'"
                    )
                }
            }

            ClickHouseBiDeploymentInspector(
                clientOptions = ClickHouseClientOptions(
                    endpoints = listOf(URI.create(clickHouse.httpUrl)),
                    username = clickHouse.username,
                    password = clickHouse.password,
                    connectionTimeout = Duration.ofSeconds(5),
                    socketTimeout = Duration.ofSeconds(10),
                ),
                inspectionTimeout = Duration.ofSeconds(10),
            ).use { inspector ->
                val available = inspector.inspect(options).block() as BiDeploymentInspection.Available
                val observed = available.deployment.objects.single { it.key == BiObjectKey(DATABASE, TABLE) }
                val queue = available.deployment.objects.single {
                    it.key == BiObjectKey(CONSUMER_DATABASE, QUEUE)
                }

                observed.database.assert().isEqualTo(DATABASE)
                observed.name.assert().isEqualTo(TABLE)
                observed.engine.assert().isEqualTo("ReplacingMergeTree")
                observed.engineFull.assert().contains("ReplacingMergeTree")
                observed.createTableQuery.assert().contains("CREATE TABLE", TABLE)
                observed.metadata.assert().isEqualTo(storeMetadata)

                queue.engine.assert().isEqualTo("Kafka")
                queue.engineFull.assert()
                    .startsWith("Kafka(")
                    .contains("'$expectedGroup'", "SETTINGS kafka_num_consumers = 2")
                queue.metadata.assert().isEqualTo(queueMetadata)

                val changedOptions = options.copy(
                    kafkaBootstrapServers = "changed-kafka:9092",
                    topicPrefix = "changed.",
                    kafkaOffsetStorage = KafkaOffsetStorage.KEEPER,
                    kafkaKeeperPathPrefix = "/changed/wow-bi",
                )
                val changedDescriptor = BiDeploymentDescriptor.from(changedOptions)
                val changedInspection = inspector.inspect(changedOptions).block()
                    as BiDeploymentInspection.Available
                assertThrownBy<IllegalArgumentException> {
                    BiScriptGenerator(changedOptions).generate(
                        emptySet(),
                        BiScriptOperation.Deploy,
                        changedInspection,
                    )
                }.hasMessageContaining("use RESET")

                val resetInspection = inspector.inspect(changedOptions, BiScriptOperation.Reset(true)).block()
                    as BiDeploymentInspection.Available
                val reset = BiScriptGenerator(changedOptions).generate(
                    emptySet(),
                    BiScriptOperation.Reset(true),
                    resetInspection,
                )
                reset.script.assert()
                    .contains(
                        "DROP TABLE IF EXISTS \"$CONSUMER_DATABASE\".\"$QUEUE\"",
                        "\"configurationFingerprint\":\"${changedDescriptor.configurationFingerprint}\"",
                        "\"phase\":\"RESETTING\"",
                        "\"phase\":\"STABLE\"",
                    )
            }
        }
    }

    @Test
    fun `should inspect every configured cluster replica`() {
        ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE))
            .withCopyFileToContainer(
                MountableFile.forClasspathResource(CLUSTER_CONFIG_RESOURCE),
                CLUSTER_CONFIG_PATH,
            ).use { clickHouse ->
                clickHouse.start()
                val options = BiScriptOptions(
                    database = DATABASE,
                    consumerDatabase = CONSUMER_DATABASE,
                    consumerGroupNamespace = "native-inspector-cluster-integration",
                    topology = ClickHouseTopology.Cluster(name = CLUSTER, installation = "test"),
                )
                DriverManager.getConnection(clickHouse.jdbcUrl, clickHouse.username, clickHouse.password).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("CREATE DATABASE $DATABASE")
                        statement.execute(
                            "CREATE TABLE $DATABASE.$TABLE (id UInt64) " +
                                "ENGINE = MergeTree ORDER BY id"
                        )
                    }
                }

                ClickHouseBiDeploymentInspector(
                    clientOptions = ClickHouseClientOptions(
                        endpoints = listOf(URI.create(clickHouse.httpUrl)),
                        username = clickHouse.username,
                        password = clickHouse.password,
                    ),
                ).use { inspector ->
                    val available = inspector.inspect(options).block() as BiDeploymentInspection.Available

                    available.deployment.objects.single().name.assert().isEqualTo(TABLE)
                }
            }
    }

    private companion object {
        const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:24.8.14.39-alpine"
        const val DATABASE = "wow_bi_native_inspector"
        const val CONSUMER_DATABASE = "wow_bi_native_inspector_consumer"
        const val TABLE = "catalog_store"
        const val QUEUE = "catalog_command_queue"
        const val KAFKA_BOOTSTRAP_SERVERS = "kafka-a:9092,kafka-b:9092"
        const val CLUSTER = "test_cluster"
        const val CLUSTER_CONFIG_RESOURCE = "clickhouse-test-cluster.xml"
        const val CLUSTER_CONFIG_PATH = "/etc/clickhouse-server/config.d/clickhouse-test-cluster.xml"
    }
}
