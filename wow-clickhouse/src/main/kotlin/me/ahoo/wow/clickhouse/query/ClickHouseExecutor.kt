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
import com.clickhouse.client.api.query.QueryResponse
import com.clickhouse.client.api.query.QuerySettings
import com.clickhouse.data.ClickHouseFormat
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.serialization.toObjectNode
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * One statement and its server-side parameters. Every caller value is bound as a typed parameter (`{p0:String}`);
 * the SQL text holds only identifiers from the compiled schema, function names and finite numeric constants.
 */
class ClickHouseStatement(val sql: String, val parameters: Map<String, Any>)

/**
 * Runs statements over the HTTP client and decodes `JSONEachRow`, one record per line.
 *
 * The client answers with a future; the body is read on [scheduler], a bounded pool that rejects work beyond its
 * queue rather than letting slow analytical queries pile up. Each statement carries its own query id: when a
 * subscriber cancels, the request is abandoned and `KILL QUERY` stops the statement on the server, since an
 * abandoned HTTP read does not always reach it.
 */
class ClickHouseExecutor(
    private val client: Client,
    private val scheduler: Scheduler = DEFAULT_SCHEDULER,
) {
    fun rows(statement: ClickHouseStatement, settings: Map<String, String> = emptyMap()): Flux<ObjectNode> =
        Flux.defer {
            val queryId = UUID.randomUUID().toString()
            Mono.fromFuture { client.query(statement.sql, statement.parameters, querySettings(queryId, settings)) }
                .flatMapMany(::decode)
                .doOnCancel { kill(queryId) }
        }

    private fun decode(response: QueryResponse): Flux<ObjectNode> = Flux.using(
        { response },
        { open ->
            val reader = open.inputStream.bufferedReader()
            Flux.fromIterable(Iterable { reader.lineSequence().filter(String::isNotBlank).iterator() })
                .map(String::toObjectNode)
        },
        QueryResponse::close,
    ).subscribeOn(scheduler)

    /** Reads a table's columns and engine. */
    fun table(database: String, name: String): Mono<ClickHouseTable> {
        val parameters = mapOf<String, Any>("database" to database, "table" to name)
        val engine = rows(
            ClickHouseStatement(
                "SELECT engine FROM system.tables WHERE database = {database:String} AND name = {table:String}",
                parameters,
            ),
        ).map { it.get("engine").asString() }.next().switchIfEmpty(
            Mono.error { QuerySchemaUnavailableException("ClickHouse table [$database.$name] does not exist.") },
        )
        val columns = rows(
            ClickHouseStatement(
                "SELECT name, type FROM system.columns WHERE database = {database:String} AND table = {table:String} " +
                    "ORDER BY position",
                parameters,
            ),
        ).map { ClickHouseColumn(it.get("name").asString(), it.get("type").asString()) }.collectList()
        return Mono.zip(engine, columns).map {
            ClickHouseTable(database, name, it.t1, it.t2.associateBy(ClickHouseColumn::name))
        }
    }

    private fun kill(queryId: String) {
        client.query("KILL QUERY WHERE query_id = {id:String} ASYNC", mapOf<String, Any>("id" to queryId))
            .whenComplete { response, error ->
                response?.close()
                error?.let { log.debug(it) { "Unable to kill ClickHouse query [$queryId]." } }
            }
    }

    private fun querySettings(queryId: String, settings: Map<String, String>): QuerySettings = QuerySettings()
        .setFormat(ClickHouseFormat.JSONEachRow)
        .setQueryId(queryId)
        .apply { (OUTPUT_SETTINGS + settings).forEach { (name, value) -> serverSetting(name, value) } }

    companion object {
        private val log = KotlinLogging.logger { }

        /** How every statement writes its rows: plain JSON numbers, ISO-8601 instants. */
        private val OUTPUT_SETTINGS: Map<String, String> = mapOf(
            "output_format_json_quote_64bit_integers" to "0",
            "output_format_json_quote_decimals" to "0",
            "output_format_json_quote_denormals" to "0",
            "date_time_output_format" to "iso",
            "cancel_http_readonly_queries_on_client_close" to "1",
        )

        private val DEFAULT_SCHEDULER: Scheduler by lazy {
            Schedulers.newBoundedElastic(
                Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE,
                Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
                "wow-clickhouse",
                60,
                true,
            )
        }
    }
}
