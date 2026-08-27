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
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import tools.jackson.core.JacksonException
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val CLICKHOUSE_CATALOG_CLEANUP_THREADS: Int = 4
private const val CLICKHOUSE_CATALOG_CLEANUP_TTL_SECONDS: Int = 60
private val CLICKHOUSE_CATALOG_CLEANUP_SCHEDULER: Scheduler = Schedulers.newBoundedElastic(
    CLICKHOUSE_CATALOG_CLEANUP_THREADS,
    Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
    "wow-bi-catalog-cleanup",
    CLICKHOUSE_CATALOG_CLEANUP_TTL_SECONDS,
    true,
)

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

@Suppress("TooManyFunctions") // Typed accessors isolate the untyped ClickHouse wire record at one boundary.
internal data class ClickHouseCatalogRecord(
    private val values: Map<String, String?>,
) {
    fun toObjectKey(): BiObjectKey = BiObjectKey(
        database = required("database"),
        name = required("name"),
    )

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

    fun toCatalogObject(): ClickHouseCatalogObject = ClickHouseCatalogObject(
        observed = toObservedObject(),
        partitionKey = values["partition_key"].orEmpty(),
        sortingKey = values["sorting_key"].orEmpty(),
        asSelect = values["as_select"].orEmpty(),
    )

    fun toCanonicalExpectedQuery(): Pair<BiObjectKey, String> = BiObjectKey(
        database = required("database"),
        name = required("name"),
    ) to required("canonical_select")

    fun toCatalogColumn(): ClickHouseCatalogColumn = ClickHouseCatalogColumn(
        database = required("database"),
        table = required("table"),
        name = required("name"),
        type = required("type"),
        position = required("position").toIntOrNull()?.takeIf { it > 0 }
            ?: error("ClickHouse BI catalog column [position] must be positive"),
    )

    fun toRegistryEntry(): BiOwnershipRegistryEntry = BiOwnershipRegistryEntry(
        key = BiObjectKey(
            database = required("object_database"),
            name = required("object_name"),
        ),
        kind = BiObjectKind.valueOf(required("kind")),
        aggregate = nullable("aggregate"),
        consumerIdentity = nullable("consumer_identity"),
        definitionFingerprint = required("definition_fingerprint"),
        revision = required("revision").toLong(),
        status = BiRegistryEntryStatus.valueOf(required("status")),
    )

    fun toRegistryRow(): ClickHouseRegistryRow = ClickHouseRegistryRow(
        entry = toRegistryEntry(),
        rowFingerprint = required("row_fingerprint"),
    )

    fun toRegistryHead(): ClickHouseRegistryHead = ClickHouseRegistryHead(
        revision = required("revision").toLong(),
        snapshotFingerprint = required("snapshot_fingerprint"),
        rowFingerprint = required("row_fingerprint"),
    )

    fun toRegistryTableDefinition(): ClickHouseRegistryTableDefinition =
        ClickHouseRegistryTableDefinition(
            key = toObjectKey(),
            engine = required("engine"),
            engineFull = required("engine_full"),
            comment = required("comment"),
            sortingKey = required("sorting_key"),
        )

    private fun required(column: String): String {
        val value = values[column]
        check(!value.isNullOrBlank()) {
            "ClickHouse BI catalog column [$column] must not be blank"
        }
        return value
    }

    private fun nullable(column: String): String? = values[column]?.takeIf(String::isNotBlank)
}

internal data class ClickHouseRegistryHead(
    val revision: Long,
    val snapshotFingerprint: String,
    val rowFingerprint: String,
)

internal data class ClickHouseRegistryRow(
    val entry: BiOwnershipRegistryEntry,
    val rowFingerprint: String,
)

internal data class ClickHouseRegistryTableDefinition(
    val key: BiObjectKey,
    val engine: String,
    val engineFull: String,
    val comment: String,
    val sortingKey: String,
)

internal data class ClickHouseCatalogObject(
    val observed: ObservedBiObject,
    val partitionKey: String,
    val sortingKey: String,
    val asSelect: String = "",
    val columns: List<ClickHouseCatalogColumn> = emptyList(),
) {
    val key: BiObjectKey
        get() = observed.key
}

internal data class ClickHouseCatalogColumn(
    val database: String,
    val table: String,
    val name: String,
    val type: String,
    val position: Int,
) {
    val key: BiObjectKey
        get() = BiObjectKey(database, table)
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
        } catch (error: CancellationException) {
            responseLifecycle.abandon()
            throwCancelledQuery(error)
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

    private fun throwCancelledQuery(cause: CancellationException): Nothing =
        throw ClientException("ClickHouse BI catalog query was cancelled", cause)

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
                CLICKHOUSE_CATALOG_CLEANUP_SCHEDULER.schedule {
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
                .useHttpFormDataForQuery(true)
                .build()
            return NativeClickHouseCatalogClient(client, options.executionTimeout)
        }

        private const val CLIENT_NAME = "wow-bi"
    }
}
