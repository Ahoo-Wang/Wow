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
import me.ahoo.wow.configuration.MetadataSearcher
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.containers.Network
import org.testcontainers.lifecycle.Startables
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.stream.Stream
import kotlin.math.ceil

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "WOW_BI_CATALOG_SCALE", matches = "(?i:true|1)")
class ClickHouseCatalogScaleIntegrationTest {
    private lateinit var network: Network
    private lateinit var primary: ClickHouseContainer
    private lateinit var replica: ClickHouseContainer

    @BeforeAll
    @Suppress("TooGenericExceptionCaught") // Any startup failure must close every partially started container.
    fun startCluster() {
        network = Network.newNetwork()
        primary = clickHouse(NODE_1)
        replica = clickHouse(NODE_2)
        try {
            Startables.deepStart(Stream.of(primary, replica)).join()
            seedCatalog(primary)
            seedCatalog(replica)
            assertClusterReady()
        } catch (error: Throwable) {
            closeCluster()
            throw error
        }
    }

    @AfterAll
    fun closeCluster() {
        if (::primary.isInitialized) {
            primary.close()
        }
        if (::replica.isInitialized) {
            replica.close()
        }
        if (::network.isInitialized) {
            network.close()
        }
    }

    @Test
    @Suppress("LongMethod") // Keeps measured setup, concurrent requests, and percentile assertions together.
    fun `should inspect ten thousand catalog objects across two replicas under fifty concurrent requests`() {
        val options = BiScriptOptions(
            database = CATALOG_DATABASE,
            consumerDatabase = CONSUMER_DATABASE,
            consumerGroupNamespace = "catalog-scale",
            topology = ClickHouseTopology.Cluster(
                name = CLUSTER,
                installation = "catalog-scale",
            ),
        )
        val aggregate = MetadataSearcher.localAggregates.first()
        val latencies = ConcurrentLinkedQueue<Long>()
        ClickHouseBiDeploymentInspector(
            clientOptions = clientOptions(primary),
            inspectionTimeout = INSPECTION_TIMEOUT,
        ).use { inspector ->
            val outcomes = Flux.range(0, REQUEST_COUNT)
                .flatMap({ request ->
                    Mono.defer {
                        val startedAt = System.nanoTime()
                        inspector.inspect(
                            options,
                            BiScriptOperation.Deploy,
                            BiScriptGenerator(options).prepare(setOf(aggregate)),
                        )
                            .map<ScaleOutcome> { inspection ->
                                val latencyMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
                                latencies.add(latencyMillis)
                                ScaleOutcome.Success(request, inspection)
                            }
                            .onErrorResume { error ->
                                Mono.just(ScaleOutcome.Failure(request, error))
                            }
                    }
                }, REQUEST_COUNT)
                .collectList()
                .block(SCALE_TIMEOUT)
                .orEmpty()

            outcomes.assert().hasSize(REQUEST_COUNT)
            val failures = outcomes.filterIsInstance<ScaleOutcome.Failure>()
            check(failures.isEmpty()) {
                "Scale inspection failures: ${failures.joinToString { "#${it.request}:${it.error}" }}"
            }
            outcomes.filterIsInstance<ScaleOutcome.Success>().all { outcome ->
                outcome.inspection is BiDeploymentInspection.Available &&
                    outcome.inspection.deployment.objects.isEmpty()
            }.assert().isTrue()
        }

        val queryStats = queryStats(primary) + queryStats(replica)
        check(queryStats.queryCount >= REQUEST_COUNT) {
            "Expected at least $REQUEST_COUNT finished scoped catalog queries, but observed $queryStats"
        }
        val latencyStats = LatencyStats.from(latencies)
        writeReport(
            "catalog-scale.json",
            """
            {
              "catalogObjectsPerReplica": $CATALOG_SIZE,
              "replicas": 2,
              "requests": $REQUEST_COUNT,
              "concurrency": $REQUEST_COUNT,
              "successes": $REQUEST_COUNT,
              "latencyMillis": ${latencyStats.toJson()},
              "queryLog": ${queryStats.toJson()}
            }
            """.trimIndent(),
        )
    }

    private fun clickHouse(alias: String): ClickHouseContainer =
        ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE))
            .withNetwork(network)
            .withNetworkAliases(alias)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource(CLUSTER_CONFIG_RESOURCE),
                CLUSTER_CONFIG_PATH,
            )

    private fun seedCatalog(container: ClickHouseContainer) {
        val client = "clickhouse-client --user ${container.username} --password ${container.password} --multiquery"
        val seedScript = """
            set -eu
            $client --query "CREATE DATABASE $CATALOG_DATABASE ENGINE = Memory; CREATE DATABASE $CONSUMER_DATABASE ENGINE = Memory;"
            seq 1 $CATALOG_SIZE | awk '{
                printf "CREATE VIEW $CATALOG_DATABASE.scale_view_%05d AS SELECT %d AS id;\n", ${'$'}1, ${'$'}1
            }' | $client
        """.trimIndent()
        val result = container.execInContainer("sh", "-c", seedScript)
        check(result.exitCode == 0) {
            "Failed to seed ClickHouse node [${container.containerName}]: ${result.stderr}\n${result.stdout}"
        }
        connection(container).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT count() FROM system.tables WHERE database = '$CATALOG_DATABASE'"
                ).use { rows ->
                    rows.next().assert().isTrue()
                    rows.getInt(1).assert().isEqualTo(CATALOG_SIZE)
                }
            }
        }
    }

    private fun assertClusterReady() {
        connection(primary).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT count(), uniqExact(hostName()) " +
                        "FROM clusterAllReplicas('$CLUSTER', system.one)"
                ).use { rows ->
                    rows.next().assert().isTrue()
                    rows.getInt(1).assert().isEqualTo(2)
                    rows.getInt(2).assert().isEqualTo(2)
                }
                statement.executeQuery(
                    "SELECT hostName(), count() " +
                        "FROM clusterAllReplicas('$CLUSTER', system.tables) " +
                        "WHERE database = '$CATALOG_DATABASE' " +
                        "GROUP BY hostName() ORDER BY hostName()"
                ).use { rows ->
                    val catalogSizes = buildList {
                        while (rows.next()) {
                            add(rows.getString(1) to rows.getInt(2))
                        }
                    }
                    catalogSizes.assert().hasSize(2)
                    catalogSizes.all { (_, size) -> size == CATALOG_SIZE }.assert().isTrue()
                }
            }
        }
    }

    private fun queryStats(container: ClickHouseContainer): QueryStats {
        connection(container).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SYSTEM FLUSH LOGS")
                statement.executeQuery(
                    """
                    SELECT count(),
                           coalesce(sum(read_rows), 0),
                           coalesce(sum(read_bytes), 0),
                           coalesce(sum(result_rows), 0),
                           coalesce(max(memory_usage), 0),
                           coalesce(quantileExact(0.95)(query_duration_ms), 0)
                    FROM system.query_log
                    WHERE type = 'QueryFinish'
                      AND event_time >= now() - INTERVAL 10 MINUTE
                      AND query LIKE '%system.tables%'
                      AND query LIKE '%startsWith(comment%'
                    """.trimIndent()
                ).use { rows ->
                    rows.next().assert().isTrue()
                    return QueryStats(
                        queryCount = rows.getLong(1),
                        readRows = rows.getLong(2),
                        readBytes = rows.getLong(3),
                        resultRows = rows.getLong(4),
                        maxMemoryUsage = rows.getLong(5),
                        p95QueryDurationMillis = rows.getLong(6),
                    )
                }
            }
        }
    }

    private fun connection(container: ClickHouseContainer) =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    private fun clientOptions(container: ClickHouseContainer): ClickHouseClientOptions = ClickHouseClientOptions(
        endpoints = listOf(URI.create(container.httpUrl)),
        username = container.username,
        password = container.password,
        connectionTimeout = Duration.ofSeconds(5),
        connectionRequestTimeout = Duration.ofSeconds(30),
        socketTimeout = Duration.ofSeconds(30),
        executionTimeout = Duration.ofSeconds(30),
    )

    private fun writeReport(fileName: String, content: String) {
        val reportDirectory = Path.of("build", "reports", "wow-bi", "catalog-scale")
        Files.createDirectories(reportDirectory)
        Files.writeString(reportDirectory.resolve(fileName), content + System.lineSeparator())
    }

    private sealed interface ScaleOutcome {
        data class Success(
            val request: Int,
            val inspection: BiDeploymentInspection,
        ) : ScaleOutcome

        data class Failure(
            val request: Int,
            val error: Throwable,
        ) : ScaleOutcome
    }

    private data class LatencyStats(
        val min: Long,
        val p50: Long,
        val p95: Long,
        val p99: Long,
        val max: Long,
    ) {
        fun toJson(): String =
            "{\"min\":$min,\"p50\":$p50,\"p95\":$p95,\"p99\":$p99,\"max\":$max}"

        companion object {
            fun from(values: Collection<Long>): LatencyStats {
                val sorted = values.sorted()
                check(sorted.isNotEmpty()) { "No successful scale inspection latency was recorded" }
                return LatencyStats(
                    min = sorted.first(),
                    p50 = sorted.percentile(0.50),
                    p95 = sorted.percentile(0.95),
                    p99 = sorted.percentile(0.99),
                    max = sorted.last(),
                )
            }

            private fun List<Long>.percentile(value: Double): Long {
                val index = ceil(size * value).toInt().coerceIn(1, size) - 1
                return this[index]
            }
        }
    }

    private data class QueryStats(
        val queryCount: Long,
        val readRows: Long,
        val readBytes: Long,
        val resultRows: Long,
        val maxMemoryUsage: Long,
        val p95QueryDurationMillis: Long,
    ) {
        operator fun plus(other: QueryStats): QueryStats = QueryStats(
            queryCount = queryCount + other.queryCount,
            readRows = readRows + other.readRows,
            readBytes = readBytes + other.readBytes,
            resultRows = resultRows + other.resultRows,
            maxMemoryUsage = maxOf(maxMemoryUsage, other.maxMemoryUsage),
            p95QueryDurationMillis = maxOf(p95QueryDurationMillis, other.p95QueryDurationMillis),
        )

        fun toJson(): String = """
            {"queryCount":$queryCount,"readRows":$readRows,"readBytes":$readBytes,"resultRows":$resultRows,"maxMemoryUsage":$maxMemoryUsage,"p95QueryDurationMillis":$p95QueryDurationMillis}
        """.trimIndent()
    }

    private companion object {
        const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:24.8.14.39-alpine"
        const val CLUSTER = "wow_bi_scale_cluster"
        const val NODE_1 = "wow-bi-scale-node-1"
        const val NODE_2 = "wow-bi-scale-node-2"
        const val CATALOG_DATABASE = "wow_bi_catalog_scale"
        const val CONSUMER_DATABASE = "wow_bi_catalog_scale_consumer"
        const val CATALOG_SIZE = 10_000
        const val REQUEST_COUNT = 50
        val INSPECTION_TIMEOUT: Duration = Duration.ofSeconds(60)
        val SCALE_TIMEOUT: Duration = Duration.ofMinutes(3)
        const val CLUSTER_CONFIG_RESOURCE = "clickhouse-scale-cluster.xml"
        const val CLUSTER_CONFIG_PATH = "/etc/clickhouse-server/config.d/clickhouse-scale-cluster.xml"
    }
}
