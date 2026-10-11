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
import me.ahoo.wow.serialization.toObjectNode
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * Runs statements and decodes `JSONEachRow`. Budgets travel as server settings, so ClickHouse enforces them; a
 * cancelled subscription cancels the request, and `cancel_http_readonly_queries_on_client_close` stops the query on
 * the server.
 */
class ClickHouseExecutor(
    private val client: Client,
    private val settings: Map<String, String> = DEFAULT_SETTINGS,
) {
    /** What the last statement of each query id cost on the server, for the spike's measurements. */
    val observations: MutableList<Observation> = java.util.Collections.synchronizedList(mutableListOf())

    data class Observation(val sql: String, val serverMillis: Long, val readRows: Long, val resultRows: Long)

    fun rows(statement: ClickHouseStatement): Flux<ObjectNode> =
        Mono.fromFuture { client.query(statement.sql, statement.parameters, querySettings()) }
            .flatMapMany(::decode)

    private fun decode(response: QueryResponse): Flux<ObjectNode> = Flux.using<ObjectNode, QueryResponse>(
        { response },
        { open ->
            Flux.defer {
                val reader = open.inputStream.bufferedReader()
                Flux.fromIterable(Iterable { reader.lineSequence().filter(String::isNotBlank).iterator() })
                    .map(String::toObjectNode)
            }.doOnComplete {
                observations.add(Observation("", open.serverTime / 1_000_000, open.readRows, open.resultRows))
            }
        },
        QueryResponse::close,
    ).subscribeOn(Schedulers.boundedElastic())

    fun table(database: String, name: String): Mono<ClickHouseTable> = rows(
        ClickHouseStatement(
            "SELECT name, type FROM system.columns WHERE database = {database:String} AND table = {table:String} " +
                "ORDER BY position",
            mapOf("database" to database, "table" to name),
        ),
    ).map { ClickHouseColumn(it.get("name").asString(), it.get("type").asString()) }
        .collectList()
        .map { columns ->
            check(columns.isNotEmpty()) { "ClickHouse table [$database.$name] does not exist or has no columns." }
            ClickHouseTable(database, name, columns.associateBy(ClickHouseColumn::name))
        }

    private fun querySettings(): QuerySettings = QuerySettings()
        .setFormat(ClickHouseFormat.JSONEachRow)
        .setQueryId(UUID.randomUUID().toString())
        .apply { settings.forEach { (name, value) -> serverSetting(name, value) } }

    companion object {
        val DEFAULT_SETTINGS: Map<String, String> = mapOf(
            "output_format_json_quote_64bit_integers" to "0",
            "output_format_json_quote_decimals" to "0",
            "date_time_output_format" to "iso",
            "cancel_http_readonly_queries_on_client_close" to "1",
            "max_execution_time" to "30",
            "timeout_overflow_mode" to "throw",
        )
    }
}
