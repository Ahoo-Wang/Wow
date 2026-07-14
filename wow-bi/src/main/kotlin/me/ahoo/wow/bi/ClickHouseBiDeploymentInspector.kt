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
import com.clickhouse.client.api.query.QueryResponse
import com.clickhouse.client.api.query.QuerySettings
import com.clickhouse.data.ClickHouseFormat
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import tools.jackson.core.JacksonException
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads Wow BI ownership markers directly from the ClickHouse system catalog.
 *
 * This inspector owns its ClickHouse client. Call [close] when the inspector is no longer used.
 */
class ClickHouseBiDeploymentInspector internal constructor(
    private val catalogClient: ClickHouseCatalogClient,
    private val inspectionTimeout: Duration = DEFAULT_INSPECTION_TIMEOUT,
    private val timeoutScheduler: Scheduler = Schedulers.parallel(),
) : BiDeploymentInspector, AutoCloseable {
    constructor(
        clientOptions: ClickHouseClientOptions,
        inspectionTimeout: Duration = DEFAULT_INSPECTION_TIMEOUT,
    ) : this(
        catalogClient = createCatalogClient(clientOptions, inspectionTimeout),
        inspectionTimeout = inspectionTimeout,
    )

    init {
        inspectionTimeout.requireValidTimeout("inspectionTimeout")
    }

    override fun inspect(
        options: BiScriptOptions,
        operation: BiScriptOperation,
    ): Mono<BiDeploymentInspection> =
        Mono.defer {
            val cancellation = ClickHouseQueryCancellation()
            Mono.fromCallable<BiDeploymentInspection> {
                try {
                    val deployment = when (val topology = options.topology) {
                        ClickHouseTopology.Standalone -> inspectStandalone(options, operation, cancellation)
                        is ClickHouseTopology.Cluster -> inspectCluster(options, topology, operation, cancellation)
                    }
                    BiDeploymentInspection.Available(deployment)
                } catch (error: BiDeploymentInspectionException) {
                    error.cancelledInspectionOrThrow(cancellation)
                } catch (error: IllegalArgumentException) {
                    BiDeploymentInspectionException.Inconsistent(
                        error.message ?: "ClickHouse BI catalog is inconsistent",
                        error,
                    ).cancelledInspectionOrThrow(cancellation)
                } catch (error: IllegalStateException) {
                    BiDeploymentInspectionException.Inconsistent(
                        error.message ?: "ClickHouse BI catalog is inconsistent",
                        error,
                    ).cancelledInspectionOrThrow(cancellation)
                } catch (error: ClientException) {
                    error.toInspectionException().cancelledInspectionOrThrow(cancellation)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    error.cancelledInspectionOrThrow(cancellation)
                } finally {
                    if (cancellation.isCancelled) {
                        Thread.interrupted()
                    }
                }
            }
                .subscribeOn(Schedulers.boundedElastic())
                .doOnCancel(cancellation::cancel)
                .timeout(inspectionTimeout, timeoutScheduler)
        }
            .onErrorMap(TimeoutException::class.java) { error ->
                BiDeploymentInspectionException.Timeout(
                    message = "ClickHouse BI deployment inspection timed out",
                    cause = error,
                )
            }

    override fun close() {
        catalogClient.close()
    }

    private fun inspectStandalone(
        options: BiScriptOptions,
        operation: BiScriptOperation,
        cancellation: ClickHouseQueryCancellation,
    ): ObservedBiDeployment {
        val records = catalogClient.query(
            sql = STANDALONE_CATALOG_QUERY,
            parameters = options.catalogParameters(),
            columns = CATALOG_COLUMNS,
            cancellation = cancellation,
        )
        return validateObjects(options, operation, records.map(ClickHouseCatalogRecord::toObservedObject))
    }

    private fun inspectCluster(
        options: BiScriptOptions,
        cluster: ClickHouseTopology.Cluster,
        operation: BiScriptOperation,
        cancellation: ClickHouseQueryCancellation,
    ): ObservedBiDeployment {
        val parameters = options.catalogParameters() + ("cluster" to cluster.name)
        val nodes = catalogClient.query(
            sql = CLUSTER_NODES_QUERY,
            parameters = mapOf("cluster" to cluster.name),
            columns = NODE_COLUMNS,
            cancellation = cancellation,
        ).map(ClickHouseCatalogRecord::toNode).toSet()
        check(nodes.isNotEmpty()) {
            "ClickHouse BI cluster [${cluster.name}] returned no replicas"
        }

        val objects = catalogClient.query(
            sql = CLUSTER_CATALOG_QUERY,
            parameters = parameters,
            columns = NODE_COLUMNS + CATALOG_COLUMNS,
            cancellation = cancellation,
        ).map { record -> NodeObject(record.toNode(), record.toObservedObject()) }
        validateClusterCatalog(cluster.name, nodes, objects)
        return validateObjects(options, operation, objects.map(NodeObject::objectValue))
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

        objects.groupBy { it.objectValue.key }.forEach { (key, replicas) ->
            if (replicas.none { it.objectValue.metadata != null }) {
                return@forEach
            }
            check(replicas.size == nodes.size && replicas.mapTo(mutableSetOf(), NodeObject::node) == nodes) {
                "ClickHouse BI catalog object [${key.database}.${key.name}] is missing from a replica"
            }
            val definitions = replicas.map { it.objectValue.toCatalogDefinition() }.distinct()
            check(definitions.size == 1) {
                "ClickHouse BI catalog object [${key.database}.${key.name}] differs across replicas"
            }
        }
    }

    private fun validateObjects(
        options: BiScriptOptions,
        operation: BiScriptOperation,
        objects: List<ObservedBiObject>,
    ): ObservedBiDeployment {
        val uniqueObjects = objects.groupBy(ObservedBiObject::key).map { (key, replicas) ->
            val definitions = replicas.map(ObservedBiObject::toCatalogDefinition).distinct()
            check(definitions.size == 1 || replicas.none { it.metadata != null }) {
                "ClickHouse BI catalog object [${key.database}.${key.name}] has duplicate definitions"
            }
            replicas.first()
        }.sortedWith(compareBy<ObservedBiObject> { it.database }.thenBy { it.name })
        val descriptor = BiDeploymentDescriptor.from(options)
        val requestedDeploymentMetadata = uniqueObjects.asSequence()
            .mapNotNull(ObservedBiObject::metadata)
            .filter { metadata -> metadata.deploymentId == descriptor.deploymentId }
            .toList()
        val requestedDeploymentIsStable = requestedDeploymentMetadata.all { metadata ->
            metadata.phase == BiDeploymentPhase.STABLE &&
                metadata.configurationFingerprint == descriptor.configurationFingerprint
        }
        uniqueObjects.filter { it.metadata?.kind == BiObjectKind.QUEUE }
            .forEach { queue ->
                val validateRequestedConfiguration =
                    operation == BiScriptOperation.Deploy &&
                        requestedDeploymentIsStable &&
                        queue.metadata?.deploymentId == descriptor.deploymentId
                validateQueueIdentity(options, queue, validateRequestedConfiguration)
            }
        return ObservedBiDeployment(uniqueObjects)
    }

    private fun validateQueueIdentity(
        options: BiScriptOptions,
        queue: ObservedBiObject,
        validateRequestedConfiguration: Boolean,
    ) {
        check(queue.engine == "Kafka") {
            "Owned BI queue [${queue.database}.${queue.name}] must use the Kafka engine"
        }
        val metadata = checkNotNull(queue.metadata)
        val identity = checkNotNull(metadata.consumerIdentity) {
            "Owned BI queue [${queue.database}.${queue.name}] is missing consumerIdentity"
        }
        val consumerName = queue.name.removeSuffix("_queue") + "_consumer"
        val expectedGroup = "wow-bi.$identity.$consumerName"
        val arguments = queue.engineFull.functionArguments("Kafka").orEmpty()
        val actualGroup = arguments.getOrNull(KAFKA_GROUP_ARGUMENT_INDEX)
        check(actualGroup == ClickHouseSqlSyntax.stringLiteral(expectedGroup)) {
            "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka consumer group"
        }
        val streamKind = when {
            queue.name.endsWith("_command_queue") -> "command"
            queue.name.endsWith("_state_queue") -> "state"
            else -> error("Owned BI queue [${queue.database}.${queue.name}] has an unsupported queue name")
        }
        check(arguments.getOrNull(KAFKA_FORMAT_ARGUMENT_INDEX) == ClickHouseSqlSyntax.stringLiteral(KAFKA_FORMAT)) {
            "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka format"
        }
        if (!validateRequestedConfiguration) {
            return
        }
        check(
            arguments.getOrNull(KAFKA_BROKERS_ARGUMENT_INDEX) ==
                ClickHouseSqlSyntax.stringLiteral(options.kafkaBootstrapServers)
        ) {
            "Owned BI queue [${queue.database}.${queue.name}] has unexpected Kafka bootstrap servers"
        }
        val expectedTopic = "${options.topicPrefix}${checkNotNull(metadata.aggregate)}.$streamKind"
        check(arguments.getOrNull(KAFKA_TOPIC_ARGUMENT_INDEX) == ClickHouseSqlSyntax.stringLiteral(expectedTopic)) {
            "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka topic"
        }
        val actualKeeperPath = queue.engineFull.settingLiteral(KAFKA_KEEPER_PATH_SETTING)
        val actualReplicaName = queue.engineFull.settingLiteral(KAFKA_REPLICA_NAME_SETTING)
        when (options.kafkaOffsetStorage) {
            KafkaOffsetStorage.BROKER -> check(actualKeeperPath == null && actualReplicaName == null) {
                "Owned BI queue [${queue.database}.${queue.name}] has unexpected Keeper offset settings"
            }

            KafkaOffsetStorage.KEEPER -> {
                val expectedKeeperPath = "${options.kafkaKeeperPathPrefix.trimEnd('/')}/$identity/${queue.name}"
                check(actualKeeperPath == ClickHouseSqlSyntax.stringLiteral(expectedKeeperPath)) {
                    "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka Keeper path"
                }
                val expectedReplicaName = when (options.topology) {
                    is ClickHouseTopology.Cluster -> "{replica}"
                    ClickHouseTopology.Standalone -> identity
                }
                check(actualReplicaName == ClickHouseSqlSyntax.stringLiteral(expectedReplicaName)) {
                    "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka Keeper replica name"
                }
            }
        }
    }

    private data class NodeObject(
        val node: ClickHouseCatalogNode,
        val objectValue: ObservedBiObject,
    )

    private companion object {
        val DEFAULT_INSPECTION_TIMEOUT: Duration = Duration.ofSeconds(30)
        const val KAFKA_BROKERS_ARGUMENT_INDEX: Int = 0
        const val KAFKA_TOPIC_ARGUMENT_INDEX: Int = 1
        const val KAFKA_GROUP_ARGUMENT_INDEX: Int = 2
        const val KAFKA_FORMAT_ARGUMENT_INDEX: Int = 3
        const val KAFKA_FORMAT: String = "JSONAsString"
        const val KAFKA_KEEPER_PATH_SETTING: String = "kafka_keeper_path"
        const val KAFKA_REPLICA_NAME_SETTING: String = "kafka_replica_name"

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
            SETTINGS show_table_uuid_in_table_create_query_if_not_nil = 0
        """.trimIndent()

        val CLUSTER_NODES_QUERY: String = """
            SELECT hostName() AS host_name, tcpPort() AS tcp_port
            FROM clusterAllReplicas({cluster:String}, system.one)
            SETTINGS skip_unavailable_shards = 0,
                     show_table_uuid_in_table_create_query_if_not_nil = 0
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
            SETTINGS skip_unavailable_shards = 0,
                     show_table_uuid_in_table_create_query_if_not_nil = 0
        """.trimIndent()

        fun createCatalogClient(
            options: ClickHouseClientOptions,
            inspectionTimeout: Duration,
        ): NativeClickHouseCatalogClient {
            inspectionTimeout.requireValidTimeout("inspectionTimeout")
            require(options.socketTimeout <= inspectionTimeout) {
                "socketTimeout [${options.socketTimeout}] must not exceed inspectionTimeout [$inspectionTimeout]"
            }
            return NativeClickHouseCatalogClient.create(options)
        }
    }
}

internal interface ClickHouseCatalogClient : AutoCloseable {
    fun query(
        sql: String,
        parameters: Map<String, Any>,
        columns: List<String>,
    ): List<ClickHouseCatalogRecord>

    fun query(
        sql: String,
        parameters: Map<String, Any>,
        columns: List<String>,
        cancellation: ClickHouseQueryCancellation,
    ): List<ClickHouseCatalogRecord> = query(sql, parameters, columns)
}

internal class ClickHouseQueryCancellation {
    private val cancelled = AtomicBoolean()
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    val isCancelled: Boolean
        get() = cancelled.get()

    fun invokeOnCancel(callback: () -> Unit): AutoCloseable {
        if (cancelled.get()) {
            callback()
            return AutoCloseable { }
        }
        callbacks += callback
        if (cancelled.get() && callbacks.remove(callback)) {
            callback()
        }
        return AutoCloseable { callbacks.remove(callback) }
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return
        }
        callbacks.forEach { callback ->
            if (callbacks.remove(callback)) {
                callback()
            }
        }
    }
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

    fun toObservedObject(): ObservedBiObject {
        val database = required("database")
        val name = required("name")
        val metadata = try {
            BiObjectMetadataCodec.decode(values["comment"].orEmpty())
        } catch (error: JacksonException) {
            throw BiDeploymentInspectionException.Inconsistent(
                "ClickHouse BI catalog object [$database.$name] contains invalid ownership metadata",
                error,
            )
        }
        return ObservedBiObject(
            database = database,
            name = name,
            engine = required("engine"),
            engineFull = values["engine_full"].orEmpty(),
            createTableQuery = values["create_table_query"].orEmpty(),
            metadata = metadata,
        )
    }

    private fun required(column: String): String {
        val value = values[column]
        check(!value.isNullOrBlank()) {
            "ClickHouse BI catalog column [$column] must not be blank"
        }
        return value
    }
}

internal class NativeClickHouseCatalogClient internal constructor(
    private val client: Client,
    private val executionTimeout: Duration = Duration.ZERO,
) : ClickHouseCatalogClient {
    override fun query(
        sql: String,
        parameters: Map<String, Any>,
        columns: List<String>,
    ): List<ClickHouseCatalogRecord> = query(
        sql = sql,
        parameters = parameters,
        columns = columns,
        cancellation = ClickHouseQueryCancellation(),
    )

    override fun query(
        sql: String,
        parameters: Map<String, Any>,
        columns: List<String>,
        cancellation: ClickHouseQueryCancellation,
    ): List<ClickHouseCatalogRecord> {
        if (cancellation.isCancelled) {
            throwCancelledQuery()
        }
        val settings = QuerySettings()
            .setFormat(ClickHouseFormat.RowBinaryWithNamesAndTypes)
            .waitEndOfQuery(true)
        val responseFuture = client.query(sql, parameters, settings)
        val responseLifecycle = QueryResponseLifecycle(responseFuture)
        val queryThread = Thread.currentThread()
        val cancellationRegistration = cancellation.invokeOnCancel {
            if (responseLifecycle.cancel()) {
                queryThread.interrupt()
                responseLifecycle.cleanupCancelled()
            }
        }
        try {
            val response = responseFuture.awaitResponse(responseLifecycle)
            return responseLifecycle.use {
                client.newBinaryFormatReader(response).use { reader ->
                    buildList {
                        while (reader.next() != null) {
                            add(ClickHouseCatalogRecord(columns.associateWith(reader::getString)))
                        }
                    }
                }
            }
        } finally {
            cancellationRegistration.close()
        }
    }

    private fun CompletableFuture<QueryResponse>.awaitResponse(
        responseLifecycle: QueryResponseLifecycle,
    ): QueryResponse {
        val response = try {
            if (executionTimeout.isZero) {
                get()
            } else {
                get(executionTimeout.toNanos(), TimeUnit.NANOSECONDS)
            }
        } catch (error: InterruptedException) {
            responseLifecycle.abandon()
            Thread.currentThread().interrupt()
            throwInterruptedQuery(error)
        } catch (error: TimeoutException) {
            responseLifecycle.abandon()
            throwTimedOutQuery(error)
        } catch (error: ExecutionException) {
            responseLifecycle.abandon()
            throwFailedQuery(error.cause)
        }
        if (!responseLifecycle.claim(response)) {
            throwCancelledQuery()
        }
        return response
    }

    private fun throwInterruptedQuery(cause: InterruptedException): Nothing =
        throw ClientException("ClickHouse BI catalog query was interrupted", cause)

    private fun throwTimedOutQuery(cause: TimeoutException): Nothing =
        throw ClientException("ClickHouse BI catalog query timed out", cause)

    private fun throwFailedQuery(cause: Throwable?): Nothing =
        throw ClientException("Failed to get ClickHouse BI catalog query response", cause)

    private fun throwCancelledQuery(): Nothing =
        throw ClientException("ClickHouse BI catalog query was cancelled")

    private class QueryResponseLifecycle(
        responseFuture: CompletableFuture<QueryResponse>,
    ) : AutoCloseable {
        private val state = AtomicReference(QueryResponseState.PENDING)
        private val response = AtomicReference<QueryResponse?>()
        private val responseClosed = AtomicBoolean()
        private val cleanupScheduled = AtomicBoolean()

        init {
            responseFuture.whenComplete { completedResponse, _ ->
                if (completedResponse != null) {
                    response.compareAndSet(null, completedResponse)
                    if (state.get() == QueryResponseState.CANCELLED) {
                        cleanupCancelled()
                    }
                }
            }
        }

        fun claim(completedResponse: QueryResponse): Boolean {
            response.compareAndSet(null, completedResponse)
            if (state.compareAndSet(QueryResponseState.PENDING, QueryResponseState.CLAIMED)) {
                return true
            }
            closeResponse(propagateFailure = false)
            return false
        }

        fun abandon() {
            if (transitionToCancelled()) {
                cleanupCancelled()
            }
        }

        fun cancel(): Boolean = transitionToCancelled()

        fun cleanupCancelled() {
            if (state.get() != QueryResponseState.CANCELLED || response.get() == null) {
                return
            }
            if (cleanupScheduled.compareAndSet(false, true)) {
                Schedulers.boundedElastic().schedule {
                    closeResponse(propagateFailure = false)
                }
            }
        }

        private fun transitionToCancelled(): Boolean {
            while (true) {
                when (val currentState = state.get()) {
                    QueryResponseState.PENDING,
                    QueryResponseState.CLAIMED,
                    -> if (state.compareAndSet(currentState, QueryResponseState.CANCELLED)) {
                        return true
                    }

                    QueryResponseState.CANCELLED,
                    QueryResponseState.COMPLETED,
                    -> return false
                }
            }
        }

        override fun close() {
            while (true) {
                when (val currentState = state.get()) {
                    QueryResponseState.PENDING,
                    QueryResponseState.CLAIMED,
                    -> if (state.compareAndSet(currentState, QueryResponseState.COMPLETED)) {
                        closeResponse(propagateFailure = true)
                        return
                    }

                    QueryResponseState.CANCELLED -> {
                        closeResponse(propagateFailure = false)
                        return
                    }

                    QueryResponseState.COMPLETED -> return
                }
            }
        }

        private fun closeResponse(propagateFailure: Boolean) {
            val claimedResponse = response.get() ?: return
            if (!responseClosed.compareAndSet(false, true)) {
                return
            }
            if (propagateFailure) {
                claimedResponse.close()
            } else {
                runCatching { claimedResponse.close() }
            }
        }
    }

    private enum class QueryResponseState {
        PENDING,
        CLAIMED,
        CANCELLED,
        COMPLETED,
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
                .useAsyncRequests(true)
                .setMaxConnections(options.maxConnections)
                .setMaxRetries(options.maxRetries)
                .setClientName(CLIENT_NAME)
                .build()
            return NativeClickHouseCatalogClient(client, options.executionTimeout)
        }

        private const val CLIENT_NAME = "wow-bi"
    }
}

private fun BiScriptOptions.catalogParameters(): Map<String, Any> = mapOf(
    "database" to database,
    "consumerDatabase" to consumerDatabase,
)

private fun Throwable.cancelledInspectionOrThrow(
    cancellation: ClickHouseQueryCancellation,
): BiDeploymentInspection {
    if (cancellation.isCancelled) {
        return BiDeploymentInspection.Unavailable
    }
    throw this
}

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

private fun String.functionArguments(functionName: String): List<String>? =
    FunctionArgumentsParser(trim(), functionName).parse()

private fun String.settingLiteral(name: String): String? =
    Regex("""\b${Regex.escape(name)}\s*=\s*('(?:\\.|''|[^'])*')""")
        .find(this)
        ?.groupValues
        ?.get(1)

private class FunctionArgumentsParser(
    private val expression: String,
    private val functionName: String,
) {
    private var index: Int = 0
    private var argumentStart: Int = 0
    private var activeQuote: Char? = null
    private var nestedDepth: Int = 0
    private val arguments = mutableListOf<String>()

    fun parse(): List<String>? {
        if (!moveToArgumentsStart()) {
            return null
        }
        while (index < expression.length) {
            val action = activeQuote?.let { quote -> consumeQuoted(expression[index], quote) }
                ?: consumeUnquoted(expression[index])
            when (action) {
                ParseAction.COMPLETE -> return arguments
                ParseAction.INVALID -> return null
                ParseAction.CONTINUE -> index++
            }
        }
        return null
    }

    private fun moveToArgumentsStart(): Boolean {
        if (!expression.startsWith(functionName)) {
            return false
        }
        index = functionName.length
        while (index < expression.length && expression[index].isWhitespace()) {
            index++
        }
        if (expression.getOrNull(index) != '(') {
            return false
        }
        argumentStart = ++index
        return true
    }

    private fun consumeQuoted(character: Char, quote: Char): ParseAction {
        when {
            character == '\\' -> index++
            character == quote && expression.getOrNull(index + 1) == quote -> index++
            character == quote -> activeQuote = null
        }
        return ParseAction.CONTINUE
    }

    private fun consumeUnquoted(character: Char): ParseAction = when (character) {
        '\'', '"' -> {
            activeQuote = character
            ParseAction.CONTINUE
        }
        '(', '[', '{' -> {
            nestedDepth++
            ParseAction.CONTINUE
        }
        ']', '}' -> closeNestedExpression()
        ',' -> splitArgument()
        ')' -> closeFunction()
        else -> ParseAction.CONTINUE
    }

    private fun closeNestedExpression(): ParseAction {
        if (nestedDepth == 0) {
            return ParseAction.INVALID
        }
        nestedDepth--
        return ParseAction.CONTINUE
    }

    private fun splitArgument(): ParseAction {
        if (nestedDepth == 0) {
            arguments += expression.substring(argumentStart, index).trim()
            argumentStart = index + 1
        }
        return ParseAction.CONTINUE
    }

    private fun closeFunction(): ParseAction {
        if (nestedDepth > 0) {
            nestedDepth--
            return ParseAction.CONTINUE
        }
        val lastArgument = expression.substring(argumentStart, index).trim()
        if (lastArgument.isNotEmpty() || arguments.isNotEmpty()) {
            arguments += lastArgument
        }
        return ParseAction.COMPLETE
    }

    private enum class ParseAction {
        CONTINUE,
        COMPLETE,
        INVALID,
    }
}
