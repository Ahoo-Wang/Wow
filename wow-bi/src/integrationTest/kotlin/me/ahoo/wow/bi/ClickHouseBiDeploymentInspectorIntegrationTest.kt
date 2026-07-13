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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.sql.DriverManager
import java.time.Duration

class ClickHouseBiDeploymentInspectorIntegrationTest {
    @Test
    fun `should inspect the real ClickHouse system catalog through client v2`() {
        ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE)).use { clickHouse ->
            clickHouse.start()
            val options = BiScriptOptions(
                database = DATABASE,
                consumerDatabase = CONSUMER_DATABASE,
                consumerGroupNamespace = "native-inspector-integration",
                topology = ClickHouseTopology.Standalone,
            )
            val descriptor = BiDeploymentDescriptor.from(options)
            val metadata = BiObjectMetadata(
                deploymentId = descriptor.deploymentId,
                configurationFingerprint = descriptor.configurationFingerprint,
                aggregate = "integration.catalog",
                kind = BiObjectKind.STORE,
            )
            DriverManager.getConnection(clickHouse.jdbcUrl, clickHouse.username, clickHouse.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE DATABASE $DATABASE")
                    statement.execute(
                        "CREATE TABLE $DATABASE.$TABLE (id UInt64) " +
                            "ENGINE = MergeTree ORDER BY id " +
                            "COMMENT '${BiObjectMetadataCodec.encode(metadata)}'"
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
                val observed = available.deployment.objects.single()

                observed.database.assert().isEqualTo(DATABASE)
                observed.name.assert().isEqualTo(TABLE)
                observed.engine.assert().isEqualTo("MergeTree")
                observed.engineFull.assert().contains("MergeTree")
                observed.createTableQuery.assert().contains("CREATE TABLE", TABLE)
                observed.metadata.assert().isEqualTo(metadata)
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
        const val CLUSTER = "test_cluster"
        const val CLUSTER_CONFIG_RESOURCE = "clickhouse-test-cluster.xml"
        const val CLUSTER_CONFIG_PATH = "/etc/clickhouse-server/config.d/clickhouse-test-cluster.xml"
    }
}
