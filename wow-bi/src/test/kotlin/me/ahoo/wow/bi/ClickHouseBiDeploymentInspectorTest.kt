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
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import tools.jackson.core.JacksonException
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class ClickHouseBiDeploymentInspectorTest {
    @Test
    fun `should configure and close the official client without opening a connection`() {
        val inspector = ClickHouseBiDeploymentInspector(
            ClickHouseClientOptions(
                endpoints = listOf(URI.create("http://clickhouse:8123")),
                username = "bi-user",
                password = "secret",
                connectionPoolEnabled = false,
                connectionTimeout = Duration.ofSeconds(1),
                connectionRequestTimeout = Duration.ofSeconds(2),
                socketTimeout = Duration.ZERO,
                executionTimeout = Duration.ZERO,
                maxConnections = 3,
                maxRetries = 1,
            ),
            inspectionTimeout = Duration.ofSeconds(4),
        )

        inspector.close()
    }

    @Test
    fun `should inspect an authoritative empty standalone catalog off the caller thread`() {
        val queryThread = AtomicReference<String>()
        val client = StubClickHouseCatalogClient { sql, parameters, columns ->
            queryThread.set(Thread.currentThread().name)
            sql.assert().contains("FROM system.tables").doesNotContain("clusterAllReplicas")
            parameters.assert().isEqualTo(
                mapOf(
                    "database" to OPTIONS.database,
                    "consumerDatabase" to OPTIONS.consumerDatabase,
                )
            )
            columns.assert().containsExactlyElementsOf(CATALOG_COLUMNS)
            emptyList()
        }

        val inspection = ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).block()
            as BiDeploymentInspection.Available

        inspection.deployment.objects.assert().isEmpty()
        queryThread.get().assert().startsWith("boundedElastic-")
    }

    @Test
    fun `should decode owned objects and validate the Kafka consumer identity`() {
        val identity = BiConsumerIdentity.deterministic(DESCRIPTOR)
        val metadata = BiObjectMetadata(
            deploymentId = DESCRIPTOR.deploymentId,
            configurationFingerprint = DESCRIPTOR.configurationFingerprint,
            aggregate = "example.order",
            kind = BiObjectKind.QUEUE,
            consumerIdentity = identity.value,
        )
        val client = StubClickHouseCatalogClient(
            records(
                catalogRecord(
                    database = OPTIONS.consumerDatabase,
                    name = "example_order_command_queue",
                    engine = "Kafka",
                    engineFull = "Kafka('kafka:9092', 'topic', " +
                        "'wow-bi.${identity.value}.example_order_command_consumer', 'JSONAsString')",
                    comment = BiObjectMetadataCodec.encode(metadata),
                )
            )
        )

        val available = ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).block()
            as BiDeploymentInspection.Available

        available.deployment.objects.single().metadata.assert().isEqualTo(metadata)
    }

    @Test
    fun `should fail when cluster replicas disagree on an object definition`() {
        val client = clusterClient(
            catalogRecord(node = NODE_A, engineFull = "View", comment = OWNED_COMMENT),
            catalogRecord(node = NODE_B, engineFull = "View from another replica", comment = OWNED_COMMENT),
        )

        ClickHouseBiDeploymentInspector(client).inspect(CLUSTER_OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("differs across replicas")
            }
            .verify()
    }

    @Test
    fun `should fail when an object is missing from a cluster replica`() {
        val client = clusterClient(catalogRecord(node = NODE_A, comment = OWNED_COMMENT))

        ClickHouseBiDeploymentInspector(client).inspect(CLUSTER_OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("is missing from a replica")
            }
            .verify()
    }

    @Test
    fun `should ignore replica-local foreign catalog differences`() {
        val client = clusterClient(
            catalogRecord(node = NODE_A, name = "owned_view", comment = OWNED_COMMENT),
            catalogRecord(node = NODE_B, name = "owned_view", comment = OWNED_COMMENT),
            catalogRecord(node = NODE_A, name = "node_local_table", engine = "MergeTree", engineFull = "A"),
            catalogRecord(node = NODE_B, name = "node_local_table", engine = "MergeTree", engineFull = "B"),
            catalogRecord(
                node = NODE_A,
                database = CLUSTER_OPTIONS.consumerDatabase,
                name = "bi_aggregate_command_queue",
                engine = "Kafka",
            ),
        )

        val available = ClickHouseBiDeploymentInspector(client).inspect(CLUSTER_OPTIONS).block()
            as BiDeploymentInspection.Available

        available.deployment.objects.map(ObservedBiObject::name).assert()
            .containsExactly("node_local_table", "owned_view", "bi_aggregate_command_queue")
    }

    @Test
    fun `should reject an empty or unknown cluster replica catalog`() {
        val emptyCluster = StubClickHouseCatalogClient(emptyList())
        ClickHouseBiDeploymentInspector(emptyCluster).inspect(CLUSTER_OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("returned no replicas")
            }
            .verify()

        val unknownReplica = clusterClient(catalogRecord(node = NODE_C, comment = OWNED_COMMENT))
        ClickHouseBiDeploymentInspector(unknownReplica).inspect(CLUSTER_OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("contains an unknown replica")
            }
            .verify()
    }

    @Test
    fun `should classify invalid cluster node ports and adapter arguments as inconsistent`() {
        val invalidPortClient = StubClickHouseCatalogClient(
            listOf(
                ClickHouseCatalogRecord(
                    mapOf(
                        "host_name" to "clickhouse",
                        "tcp_port" to "0",
                    )
                )
            )
        )
        ClickHouseBiDeploymentInspector(invalidPortClient).inspect(CLUSTER_OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("must be a valid port")
            }
            .verify()

        val failure = IllegalArgumentException("invalid catalog adapter argument")
        val invalidArgumentClient = StubClickHouseCatalogClient { _, _, _ -> throw failure }
        ClickHouseBiDeploymentInspector(invalidArgumentClient).inspect(OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.cause === failure &&
                    it.message == failure.message
            }
            .verify()
    }

    @Test
    fun `should accept a catalog that is identical on every cluster replica`() {
        val client = clusterClient(
            catalogRecord(node = NODE_A),
            catalogRecord(node = NODE_B),
        )

        val available = ClickHouseBiDeploymentInspector(client).inspect(CLUSTER_OPTIONS).block()
            as BiDeploymentInspection.Available

        available.deployment.objects.assert().hasSize(1)
    }

    @Test
    fun `should reject incomplete catalog records`() {
        val client = StubClickHouseCatalogClient(
            records(
                ClickHouseCatalogRecord(
                    mapOf(
                        "database" to OPTIONS.database,
                        "name" to "",
                        "engine" to "View",
                        "engine_full" to "View",
                        "create_table_query" to "CREATE VIEW",
                        "comment" to "",
                    )
                )
            )
        )

        ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("[name] must not be blank")
            }
            .verify()
    }

    @Test
    fun `should reject duplicate owned definitions in a standalone catalog`() {
        val client = StubClickHouseCatalogClient(
            records(
                catalogRecord(name = "owned_view", engineFull = "View A", comment = OWNED_COMMENT),
                catalogRecord(name = "owned_view", engineFull = "View B", comment = OWNED_COMMENT),
            )
        )

        ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("has duplicate definitions")
            }
            .verify()
    }

    @Test
    fun `should fail closed for every invalid owned Kafka queue identity`() {
        val identity = BiConsumerIdentity.deterministic(DESCRIPTOR)
        val validComment = queueComment(identity.value)
        val expectedGroup = "wow-bi.${identity.value}.example_order_command_consumer"
        val invalidQueues = listOf(
            catalogRecord(
                database = OPTIONS.consumerDatabase,
                name = "example_order_command_queue",
                engine = "MergeTree",
                engineFull = "MergeTree",
                comment = validComment,
            ) to "must use the Kafka engine",
            catalogRecord(
                database = OPTIONS.consumerDatabase,
                name = "example_order_command_queue",
                engine = "Kafka",
                engineFull = "Kafka('kafka:9092', 'topic', '$expectedGroup', 'JSONAsString')",
                comment = queueComment(null),
            ) to "is missing consumerIdentity",
            catalogRecord(
                database = OPTIONS.consumerDatabase,
                name = "example_order_command_queue",
                engine = "Kafka",
                engineFull = "Kafka('kafka:9092', 'topic', 'wrong-group', 'JSONAsString')",
                comment = validComment,
            ) to "has an unexpected Kafka consumer group",
        )

        invalidQueues.forEach { (record, expectedMessage) ->
            ClickHouseBiDeploymentInspector(StubClickHouseCatalogClient(records(record)))
                .inspect(OPTIONS)
                .test()
                .expectErrorMatches {
                    it is BiDeploymentInspectionException.Inconsistent &&
                        it.message!!.contains(expectedMessage)
                }
                .verify()
        }
    }

    @Test
    fun `should classify a malformed ownership marker as inconsistent`() {
        val client = StubClickHouseCatalogClient(
            records(
                catalogRecord(
                    database = OPTIONS.consumerDatabase,
                    name = "broken_anchor",
                    comment = "wow-bi:{",
                )
            )
        )

        ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.cause is JacksonException &&
                    it.errorInfo.errorCode == BiDeploymentInspectionException.INCONSISTENT_ERROR_CODE &&
                    it.message ==
                    "ClickHouse BI catalog object [${OPTIONS.consumerDatabase}.broken_anchor] " +
                    "contains invalid ownership metadata"
            }
            .verify()
    }

    @Test
    fun `should fail when inspection exceeds its total request timeout`() {
        val never = CountDownLatch(1)
        val client = StubClickHouseCatalogClient { _, _, _ ->
            never.await()
            emptyList()
        }

        ClickHouseBiDeploymentInspector(client, Duration.ofMillis(10))
            .inspect(OPTIONS)
            .test()
            .expectError(BiDeploymentInspectionException.Timeout::class.java)
            .verify()
    }

    @Test
    fun `should classify native client failures as unavailable inspection`() {
        val failure = ClientException("upstream credentials were rejected")
        val client = StubClickHouseCatalogClient { _, _, _ -> throw failure }

        ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Unavailable &&
                    it.cause === failure &&
                    it.errorInfo.errorCode == BiDeploymentInspectionException.UNAVAILABLE_ERROR_CODE
            }
            .verify()
    }

    @Test
    fun `should classify native client operation timeout as inspection timeout`() {
        val failure = ClientException("query timed out", TimeoutException("operation timed out"))
        val client = StubClickHouseCatalogClient { _, _, _ -> throw failure }

        ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Timeout &&
                    it.cause === failure &&
                    it.errorInfo.errorCode == BiDeploymentInspectionException.TIMEOUT_ERROR_CODE
            }
            .verify()
    }

    @Test
    fun `should close its owned catalog client`() {
        val client = StubClickHouseCatalogClient(emptyList())

        ClickHouseBiDeploymentInspector(client).close()

        client.closed.assert().isTrue()
    }

    private fun clusterClient(vararg catalog: ClickHouseCatalogRecord): StubClickHouseCatalogClient {
        val responses = ArrayDeque(
            listOf(
                listOf(nodeRecord(NODE_A), nodeRecord(NODE_B)),
                catalog.toList(),
            )
        )
        return StubClickHouseCatalogClient { sql, parameters, _ ->
            parameters["cluster"].assert().isEqualTo(CLUSTER.name)
            if (responses.size == 2) {
                sql.assert().contains("system.one", "clusterAllReplicas")
            } else {
                sql.assert().contains("system.tables", "clusterAllReplicas")
            }
            responses.removeFirst()
        }
    }

    private fun records(vararg records: ClickHouseCatalogRecord): List<ClickHouseCatalogRecord> = records.toList()

    private fun nodeRecord(node: ClickHouseCatalogNode): ClickHouseCatalogRecord = ClickHouseCatalogRecord(
        mapOf(
            "host_name" to node.hostName,
            "tcp_port" to node.tcpPort.toString(),
        )
    )

    private fun catalogRecord(
        node: ClickHouseCatalogNode? = null,
        database: String = OPTIONS.database,
        name: String = "foreign_view",
        engine: String = "View",
        engineFull: String = "View",
        comment: String = "",
    ): ClickHouseCatalogRecord = ClickHouseCatalogRecord(
        buildMap {
            node?.let {
                put("host_name", it.hostName)
                put("tcp_port", it.tcpPort.toString())
            }
            put("database", database)
            put("name", name)
            put("engine", engine)
            put("engine_full", engineFull)
            put("create_table_query", "CREATE VIEW")
            put("comment", comment)
        }
    )

    private fun queueComment(identity: String?): String = BiObjectMetadataCodec.encode(
        BiObjectMetadata(
            deploymentId = DESCRIPTOR.deploymentId,
            configurationFingerprint = DESCRIPTOR.configurationFingerprint,
            aggregate = "example.order",
            kind = BiObjectKind.QUEUE,
            consumerIdentity = identity,
        )
    )

    private class StubClickHouseCatalogClient(
        private val response: (
            sql: String,
            parameters: Map<String, Any>,
            columns: List<String>,
        ) -> List<ClickHouseCatalogRecord>,
    ) : ClickHouseCatalogClient {
        constructor(records: List<ClickHouseCatalogRecord>) : this({ _, _, _ -> records })

        var closed: Boolean = false
            private set

        override fun query(
            sql: String,
            parameters: Map<String, Any>,
            columns: List<String>,
        ): List<ClickHouseCatalogRecord> = response(sql, parameters, columns)

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val OPTIONS = BiScriptOptions(consumerGroupNamespace = "test", topology = ClickHouseTopology.Standalone)
        val DESCRIPTOR = BiDeploymentDescriptor.from(OPTIONS)
        val CLUSTER = ClickHouseTopology.Cluster(name = "test-cluster", installation = "test")
        val CLUSTER_OPTIONS = OPTIONS.copy(topology = CLUSTER)
        val NODE_A = ClickHouseCatalogNode("clickhouse-a", 9000)
        val NODE_B = ClickHouseCatalogNode("clickhouse-b", 9000)
        val NODE_C = ClickHouseCatalogNode("clickhouse-c", 9000)
        val OWNED_COMMENT = BiObjectMetadataCodec.encode(
            BiObjectMetadata(
                deploymentId = DESCRIPTOR.deploymentId,
                configurationFingerprint = DESCRIPTOR.configurationFingerprint,
                aggregate = "example.order",
                kind = BiObjectKind.VIEW,
            )
        )
        val CATALOG_COLUMNS = listOf(
            "database",
            "name",
            "engine",
            "engine_full",
            "create_table_query",
            "comment",
        )
    }
}
