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

package me.ahoo.wow.bi.catalog

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.ClientException
import com.clickhouse.client.api.query.QueryResponse
import com.clickhouse.client.api.query.QuerySettings
import com.clickhouse.data.ClickHouseFormat
import me.ahoo.wow.bi.BiDeploymentInspectionException
import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiObjectMetadataCodec
import me.ahoo.wow.bi.ClickHouseClientOptions
import me.ahoo.wow.bi.ObservedBiObject
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

    private fun required(column: String): String {
        val value = values[column]
        check(!value.isNullOrBlank()) {
            "ClickHouse BI catalog column [$column] must not be blank"
        }
        return value
    }
}

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
