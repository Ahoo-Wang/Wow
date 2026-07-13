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

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.ClientException
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeoutException

/**
 * Reads Wow BI ownership markers directly from the ClickHouse system catalog.
 *
 * This inspector owns its ClickHouse client. Call [close] when the inspector is no longer used.
 */
class ClickHouseBiDeploymentInspector internal constructor(
    private val catalogClient: ClickHouseCatalogClient,
    private val inspectionTimeout: Duration = DEFAULT_INSPECTION_TIMEOUT,
) : BiDeploymentInspector, AutoCloseable {
    constructor(
        clientOptions: ClickHouseClientOptions,
        inspectionTimeout: Duration = DEFAULT_INSPECTION_TIMEOUT,
    ) : this(
        catalogClient = NativeClickHouseCatalogClient.create(clientOptions),
        inspectionTimeout = inspectionTimeout,
    )

    init {
        inspectionTimeout.requireValidTimeout("inspectionTimeout")
    }

    override fun inspect(options: BiScriptOptions): Mono<BiDeploymentInspection> =
        Mono.fromCallable<BiDeploymentInspection> {
            val deployment = try {
                when (val topology = options.topology) {
                    ClickHouseTopology.Standalone -> inspectStandalone(options)
                    is ClickHouseTopology.Cluster -> inspectCluster(options, topology)
                }
            } catch (error: BiDeploymentInspectionException) {
                throw error
            } catch (error: IllegalArgumentException) {
                throw BiDeploymentInspectionException.Inconsistent(
                    error.message ?: "ClickHouse BI catalog is inconsistent",
                    error,
                )
            } catch (error: IllegalStateException) {
                throw BiDeploymentInspectionException.Inconsistent(
                    error.message ?: "ClickHouse BI catalog is inconsistent",
                    error,
                )
            } catch (error: ClientException) {
                throw error.toInspectionException()
            }
            BiDeploymentInspection.Available(deployment)
        }
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(inspectionTimeout)
            .onErrorMap(TimeoutException::class.java) { error ->
                BiDeploymentInspectionException.Timeout(
                    message = "ClickHouse BI deployment inspection timed out",
                    cause = error,
                )
            }

    override fun close() {
        catalogClient.close()
    }

    private fun inspectStandalone(options: BiScriptOptions): ObservedBiDeployment {
        val records = catalogClient.query(
            sql = STANDALONE_CATALOG_QUERY,
            parameters = options.catalogParameters(),
            columns = CATALOG_COLUMNS,
        )
        return validateObjects(records.map(ClickHouseCatalogRecord::toObservedObject))
    }

    private fun inspectCluster(
        options: BiScriptOptions,
        cluster: ClickHouseTopology.Cluster,
    ): ObservedBiDeployment {
        val parameters = options.catalogParameters() + ("cluster" to cluster.name)
        val nodes = catalogClient.query(
            sql = CLUSTER_NODES_QUERY,
            parameters = mapOf("cluster" to cluster.name),
            columns = NODE_COLUMNS,
        ).map(ClickHouseCatalogRecord::toNode).toSet()
        check(nodes.isNotEmpty()) {
            "ClickHouse BI cluster [${cluster.name}] returned no replicas"
        }

        val objects = catalogClient.query(
            sql = CLUSTER_CATALOG_QUERY,
            parameters = parameters,
            columns = NODE_COLUMNS + CATALOG_COLUMNS,
        ).map { record -> NodeObject(record.toNode(), record.toObservedObject()) }
        validateClusterCatalog(cluster.name, nodes, objects)
        return validateObjects(objects.map(NodeObject::objectValue))
    }

    private fun validateClusterCatalog(
        cluster: String,
        nodes: Set<ClickHouseCatalogNode>,
        objects: List<NodeObject>,
    ) {
        val observedNodes = objects.mapTo(mutableSetOf(), NodeObject::node)
        check(observedNodes.all(nodes::contains)) {
            "ClickHouse BI cluster [$cluster] catalog contains an unknown replica"
        }

        val keysByNode = nodes.associateWith { node ->
            objects.asSequence()
                .filter { it.node == node }
                .map { it.objectValue.key }
                .toSet()
        }
        val expectedKeys = keysByNode.values.first()
        check(keysByNode.values.all { it == expectedKeys }) {
            "ClickHouse BI cluster [$cluster] catalog differs across replicas"
        }

        objects.groupBy { it.objectValue.key }.forEach { (key, replicas) ->
            check(replicas.size == nodes.size && replicas.mapTo(mutableSetOf(), NodeObject::node) == nodes) {
                "ClickHouse BI catalog object [${key.database}.${key.name}] is missing from a replica"
            }
            val definitions = replicas.map { it.objectValue.toCatalogDefinition() }.distinct()
            check(definitions.size == 1) {
                "ClickHouse BI catalog object [${key.database}.${key.name}] differs across replicas"
            }
        }
    }

    private fun validateObjects(objects: List<ObservedBiObject>): ObservedBiDeployment {
        val uniqueObjects = objects.groupBy(ObservedBiObject::key).map { (key, replicas) ->
            val definitions = replicas.map(ObservedBiObject::toCatalogDefinition).distinct()
            check(definitions.size == 1) {
                "ClickHouse BI catalog object [${key.database}.${key.name}] has duplicate definitions"
            }
            replicas.first()
        }.sortedWith(compareBy<ObservedBiObject> { it.database }.thenBy { it.name })
        uniqueObjects.filter { it.metadata?.kind == BiObjectKind.QUEUE }.forEach(::validateQueueIdentity)
        return ObservedBiDeployment(uniqueObjects)
    }

    private fun validateQueueIdentity(queue: ObservedBiObject) {
        check(queue.engine == "Kafka") {
            "Owned BI queue [${queue.database}.${queue.name}] must use the Kafka engine"
        }
        val identity = checkNotNull(queue.metadata?.consumerIdentity) {
            "Owned BI queue [${queue.database}.${queue.name}] is missing consumerIdentity"
        }
        val consumerName = queue.name.removeSuffix("_queue") + "_consumer"
        val expectedGroup = "wow-bi.$identity.$consumerName"
        check(queue.engineFull.contains("'$expectedGroup'")) {
            "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka consumer group"
        }
    }

    private data class NodeObject(
        val node: ClickHouseCatalogNode,
        val objectValue: ObservedBiObject,
    )

    private companion object {
        val DEFAULT_INSPECTION_TIMEOUT: Duration = Duration.ofSeconds(30)

        val NODE_COLUMNS = listOf("host_name", "tcp_port")
        val CATALOG_COLUMNS = listOf(
            "database",
            "name",
            "engine",
            "engine_full",
            "create_table_query",
            "comment",
        )

        val STANDALONE_CATALOG_QUERY: String = """
            SELECT database, name, engine, engine_full, create_table_query, comment
            FROM system.tables
            WHERE database IN ({database:String}, {consumerDatabase:String})
        """.trimIndent()

        val CLUSTER_NODES_QUERY: String = """
            SELECT hostName() AS host_name, tcpPort() AS tcp_port
            FROM clusterAllReplicas({cluster:String}, system.one)
            SETTINGS skip_unavailable_shards = 0
        """.trimIndent()

        val CLUSTER_CATALOG_QUERY: String = """
            SELECT hostName() AS host_name,
                   tcpPort() AS tcp_port,
                   database,
                   name,
                   engine,
                   engine_full,
                   create_table_query,
                   comment
            FROM clusterAllReplicas({cluster:String}, system.tables)
            WHERE database IN ({database:String}, {consumerDatabase:String})
            SETTINGS skip_unavailable_shards = 0
        """.trimIndent()
    }
}

internal interface ClickHouseCatalogClient : AutoCloseable {
    fun query(
        sql: String,
        parameters: Map<String, Any>,
        columns: List<String>,
    ): List<ClickHouseCatalogRecord>
}

internal data class ClickHouseCatalogNode(
    val hostName: String,
    val tcpPort: Int,
)

internal data class ClickHouseCatalogRecord(
    private val values: Map<String, String?>,
) {
    fun toNode(): ClickHouseCatalogNode = ClickHouseCatalogNode(
        hostName = required("host_name"),
        tcpPort = required("tcp_port").toIntOrNull()?.takeIf { it in 1..65535 }
            ?: error("ClickHouse BI catalog column [tcp_port] must be a valid port"),
    )

    fun toObservedObject(): ObservedBiObject = ObservedBiObject(
        database = required("database"),
        name = required("name"),
        engine = required("engine"),
        engineFull = values["engine_full"].orEmpty(),
        createTableQuery = values["create_table_query"].orEmpty(),
        metadata = BiObjectMetadataCodec.decode(values["comment"].orEmpty()),
    )

    private fun required(column: String): String {
        val value = values[column]
        check(!value.isNullOrBlank()) {
            "ClickHouse BI catalog column [$column] must not be blank"
        }
        return value
    }
}

internal class NativeClickHouseCatalogClient private constructor(
    private val client: Client,
) : ClickHouseCatalogClient {
    override fun query(
        sql: String,
        parameters: Map<String, Any>,
        columns: List<String>,
    ): List<ClickHouseCatalogRecord> {
        return client.queryAll(sql, parameters).map { record ->
            ClickHouseCatalogRecord(columns.associateWith { column -> record.getString(column) })
        }
    }

    override fun close() {
        client.close()
    }

    companion object {
        fun create(options: ClickHouseClientOptions): NativeClickHouseCatalogClient {
            val builder = Client.Builder()
            options.endpoints.forEach { endpoint -> builder.addEndpoint(endpoint.toASCIIString()) }
            val client = builder
                .setUsername(options.username)
                .setPassword(options.password)
                .enableConnectionPool(options.connectionPoolEnabled)
                .setConnectTimeout(options.connectionTimeout.toMillis())
                .setConnectionRequestTimeout(options.connectionRequestTimeout.toMillis(), ChronoUnit.MILLIS)
                .setSocketTimeout(options.socketTimeout.toMillis())
                .setExecutionTimeout(options.executionTimeout.toMillis(), ChronoUnit.MILLIS)
                .setMaxConnections(options.maxConnections)
                .setMaxRetries(options.maxRetries)
                .setClientName(CLIENT_NAME)
                .build()
            return NativeClickHouseCatalogClient(client)
        }

        private const val CLIENT_NAME = "wow-bi"
    }
}

private fun BiScriptOptions.catalogParameters(): Map<String, Any> = mapOf(
    "database" to database,
    "consumerDatabase" to consumerDatabase,
)

private fun ObservedBiObject.toCatalogDefinition(): ClickHouseCatalogDefinition = ClickHouseCatalogDefinition(
    engine = engine,
    engineFull = engineFull,
    createTableQuery = createTableQuery,
    metadata = metadata,
)

private data class ClickHouseCatalogDefinition(
    val engine: String,
    val engineFull: String,
    val createTableQuery: String,
    val metadata: BiObjectMetadata?,
)

private fun ClientException.toInspectionException(): BiDeploymentInspectionException {
    if (generateSequence<Throwable>(this) { error -> error.cause }.any(Throwable::isTimeout)) {
        return BiDeploymentInspectionException.Timeout(
            message = "ClickHouse BI deployment inspection timed out",
            cause = this,
        )
    }
    return BiDeploymentInspectionException.Unavailable(
        message = "ClickHouse BI deployment inspection is unavailable",
        cause = this,
    )
}

private fun Throwable.isTimeout(): Boolean =
    this is TimeoutException || javaClass.simpleName.endsWith("TimeoutException")
