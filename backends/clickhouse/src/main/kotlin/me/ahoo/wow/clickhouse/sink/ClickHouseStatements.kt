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

package me.ahoo.wow.clickhouse.sink

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.insert.InsertSettings
import com.clickhouse.data.ClickHouseFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import me.ahoo.wow.clickhouse.clickhouse
import java.io.ByteArrayInputStream

/** The few statements the Sink runs, so that its logic is tested without a server. */
internal interface ClickHouseStatements {
    /** Runs the DDL statement [sql]. */
    suspend fun execute(sql: String)

    /**
     * Inserts the `JSONEachRow` [body] into [table] (quoted, in its database) as one insert deduplicated by [token]
     * (`insert_deduplication_token`), through the materialized views too
     * (`deduplicate_blocks_in_dependent_materialized_views`).
     */
    suspend fun insert(table: String, body: ByteArray, token: String)

    /** Whether [database] has a table or view named [name]. */
    suspend fun exists(database: String, name: String): Boolean

    /** The columns of [database]'s table or view [name], in order; empty when there is none. */
    suspend fun columns(database: String, name: String): List<ClickHouseColumn>

    /** The `system.tables.uuid` of [database]'s table or view [name]; null when there is none the account sees. */
    suspend fun uuid(database: String, name: String): String?

    /** The comment of [database]'s table or view [name], empty when it has none; null when there is no such one. */
    suspend fun comment(database: String, name: String): String?
}

/**
 * The statements over a client-v2 [client]. Its calls answer with futures; each is awaited on [Dispatchers.IO], since
 * the client may do part of the work on the calling thread. ClickHouse being unavailable fails a call as a
 * [me.ahoo.wow.spi.StorageUnavailableException] ([me.ahoo.wow.clickhouse.ClickHouseFailures]).
 */
internal class ClientStatements(private val client: Client) : ClickHouseStatements {
    override suspend fun execute(sql: String) {
        Dispatchers.IO.clickhouse { client.execute(sql).await().close() }
    }

    override suspend fun insert(table: String, body: ByteArray, token: String) {
        val settings = InsertSettings()
            .setDeduplicationToken(token)
            .serverSetting(DEDUPLICATE_VIEWS, "1")
        Dispatchers.IO.clickhouse {
            client.insert(table, ByteArrayInputStream(body), ClickHouseFormat.JSONEachRow, settings).await().close()
        }
    }

    override suspend fun exists(database: String, name: String): Boolean = Dispatchers.IO.clickhouse {
        client.queryAll(
            "SELECT name FROM system.tables WHERE database = {database:String} AND name = {name:String}",
            mapOf<String, Any>("database" to database, "name" to name),
        ).isNotEmpty()
    }

    override suspend fun columns(database: String, name: String): List<ClickHouseColumn> = Dispatchers.IO.clickhouse {
        client.queryAll(
            "SELECT name, type FROM system.columns WHERE database = {database:String} AND table = {name:String} " +
                "ORDER BY position",
            mapOf<String, Any>("database" to database, "name" to name),
        ).map { ClickHouseColumn(it.getString("name"), it.getString("type")) }
    }

    override suspend fun uuid(database: String, name: String): String? = Dispatchers.IO.clickhouse {
        client.queryAll(
            "SELECT toString(uuid) AS uuid FROM system.tables WHERE database = {database:String} AND name = {name:String}",
            mapOf<String, Any>("database" to database, "name" to name),
        ).firstOrNull()?.getString("uuid")
    }

    override suspend fun comment(database: String, name: String): String? = Dispatchers.IO.clickhouse {
        client.queryAll(
            "SELECT comment FROM system.tables WHERE database = {database:String} AND name = {name:String}",
            mapOf<String, Any>("database" to database, "name" to name),
        ).firstOrNull()?.getString("comment")
    }

    companion object {
        const val DEDUPLICATE_VIEWS: String = "deduplicate_blocks_in_dependent_materialized_views"
    }
}
