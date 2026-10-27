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

package me.ahoo.wow.clickhouse.query

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.ServerException
import com.clickhouse.client.api.query.QuerySettings
import com.clickhouse.data.ClickHouseFormat
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import me.ahoo.wow.clickhouse.sink.ClickHouseColumn
import me.ahoo.wow.query.error.QueryTimeoutException
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs statements over the client-v2 HTTP [client] and decodes `JSONEachRow`, one record per line, with the Wow
 * mapper, so a `Decimal` is read as its exact [java.math.BigDecimal].
 *
 * The response body is read on [dispatcher], since reading it blocks. Each statement carries its own query id: when
 * its collector is cancelled (the core's timeout, a client that went away), `KILL QUERY` stops the statement on the
 * server, since an abandoned HTTP read does not always reach it.
 *
 * A statement with a [timeout] runs under `max_execution_time`, in whole seconds rounded up;
 * ClickHouse failing it for that (`TIMEOUT_EXCEEDED`) is the core's [QueryTimeoutException].
 */
internal class ClickHouseExecutor(
    private val client: Client,
    private val settings: Map<String, String> = emptyMap(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Suppress("TooGenericExceptionCaught") // Any failure of the statement is translated, then rethrown.
    fun rows(statement: ClickHouseStatement, timeout: Duration? = null): Flow<ObjectNode> = flow {
        val queryId = UUID.randomUUID().toString()
        coroutineScope {
            val finished = AtomicBoolean(false)
            // Stops the statement on the server as soon as this flow is cancelled, even while a read blocks.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) kill(queryId)
                }
            }
            try {
                val response = client.query(
                    statement.sql,
                    statement.parameters,
                    querySettings(queryId, timeout)
                ).await()
                response.use { open ->
                    open.inputStream.bufferedReader().lineSequence().filter(String::isNotBlank).forEach { line ->
                        emit(row(line))
                    }
                }
                finished.set(true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A statement killed because this flow was cancelled fails on the server too: that is the cancellation.
                ensureActive()
                finished.set(true)
                throw translate(error, timeout)
            } finally {
                watcher.cancel()
            }
        }
    }.flowOn(dispatcher)

    /**
     * One `JSONEachRow` line as its row. A failure after the server sent the first rows ends the body with the
     * error's text (`http_write_exception_in_output_format=0`), which is never a JSON object: it fails the read, so a
     * cut-off result is never taken for a whole one.
     */
    private fun row(line: String): ObjectNode {
        if (!line.startsWith('{')) throw ClickHouseStreamException(line)
        return WowJson.mapper.readTree(line) as? ObjectNode ?: throw ClickHouseStreamException(line)
    }

    suspend fun all(statement: ClickHouseStatement, timeout: Duration? = null): List<ObjectNode> =
        rows(statement, timeout).toList()

    /** Reads the engine and the columns of a table or view; one that does not exist is unavailable. */
    suspend fun table(database: String, name: String): ClickHouseTable {
        val parameters = mapOf<String, Any>("database" to database, "table" to name)
        val engine = all(
            ClickHouseStatement(
                "SELECT engine FROM system.tables WHERE database = {database:String} AND name = {table:String}",
                parameters,
            ),
        ).firstOrNull()?.get("engine")?.stringValue()
            ?: throw QuerySchemaUnavailableException("ClickHouse table [$database.$name] does not exist.")
        val columns = all(
            ClickHouseStatement(
                "SELECT name, type FROM system.columns WHERE database = {database:String} AND table = {table:String} " +
                    "ORDER BY position",
                parameters,
            ),
        ).map { ClickHouseColumn(it.get("name").stringValue(), it.get("type").stringValue()) }
        return ClickHouseTable(database, name, engine, columns)
    }

    private fun kill(queryId: String) {
        client.query("KILL QUERY WHERE query_id = {id:String} ASYNC", mapOf<String, Any>("id" to queryId))
            .whenComplete { response, error ->
                response?.close()
                error?.let { log.debug(it) { "Unable to kill ClickHouse query [$queryId]." } }
            }
    }

    private fun querySettings(queryId: String, timeout: Duration?): QuerySettings = QuerySettings()
        .setFormat(ClickHouseFormat.JSONEachRow)
        .setQueryId(queryId)
        .apply {
            val all = OUTPUT_SETTINGS + settings + timeoutSettings(timeout)
            all.forEach { (name, value) -> serverSetting(name, value) }
        }

    companion object {
        private val log = KotlinLogging.logger { }

        /** ClickHouse's error code of a query that ran past `max_execution_time`. */
        const val TIMEOUT_EXCEEDED: Int = 159

        /** How every statement writes its rows: plain JSON numbers, ISO-8601 instants. */
        private val OUTPUT_SETTINGS: Map<String, String> = mapOf(
            "output_format_json_quote_64bit_integers" to "0",
            "output_format_json_quote_decimals" to "0",
            "output_format_json_quote_denormals" to "0",
            "date_time_output_format" to "iso",
            // A Decimal keeps its scale: 0.10, not 0.1.
            "output_format_decimal_trailing_zeros" to "1",
            "cancel_http_readonly_queries_on_client_close" to "1",
            // A failure while rows stream is written as its text, not as a JSON object a row could be taken for.
            "http_write_exception_in_output_format" to "0",
        )

        /** `max_execution_time` of [timeout] in whole seconds, rounded up and at least 1, since `0` means unlimited. */
        fun timeoutSettings(timeout: Duration?): Map<String, String> {
            if (timeout == null) return emptyMap()
            val seconds = timeout.toSeconds() + if (timeout.toNanosPart() > 0) 1 else 0
            return mapOf(
                "max_execution_time" to seconds.coerceAtLeast(1).toString(),
                "timeout_overflow_mode" to "throw",
            )
        }

        /** A server's `TIMEOUT_EXCEEDED` under [timeout] as the core's timeout; anything else as it is. */
        fun translate(error: Exception, timeout: Duration?): Exception {
            if (timeout == null) return error
            val code = generateSequence<Throwable>(error) { it.cause }.firstNotNullOfOrNull {
                when (it) {
                    is ServerException -> it.code
                    is ClickHouseStreamException -> it.code
                    else -> null
                }
            }
            return if (code == TIMEOUT_EXCEEDED) QueryTimeoutException(timeout, error) else error
        }
    }
}

/** A statement that failed on the server after its first rows were sent: the [message] is the server's error text. */
internal class ClickHouseStreamException(message: String) : RuntimeException(message) {
    /** The ClickHouse error code the text starts with (`Code: 159. DB::Exception: ...`), when it does. */
    val code: Int? = CODE.find(message)?.groupValues?.get(1)?.toIntOrNull()

    private companion object {
        val CODE = Regex("^Code: (\\d+)\\.")
    }
}
