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

package me.ahoo.wow.postgres.query

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ahoo.wow.postgres.autoCommitted
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import java.sql.SQLException
import javax.sql.DataSource

/**
 * The storage adapter of one aggregate type's snapshot table (decision D161): it reads the table's columns from the
 * catalog (`pg_attribute`) and compiles the facts they prove ([PostgresSnapshotStorageAdapter.facts]), as 9.x read a
 * MongoDB collection's validator or an Elasticsearch index's mapping. A table that does not exist yet, before the
 * aggregate type's first snapshot, yields provisional facts of the table it will be created as, so the Catalog asks
 * again on the next load.
 */
internal class PostgresQuerySchemaAdapter internal constructor(
    private val dataSource: DataSource,
    private val tables: PostgresSnapshotTables,
    private val dispatcher: CoroutineDispatcher,
) : QueryStorageAdapter {
    override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts = withContext(dispatcher) {
        val columns = try {
            columns()
        } catch (error: SQLException) {
            throw QuerySchemaUnavailableException(
                "Failed to read the PostgreSQL snapshot table ${tables.snapshot}.",
                error
            )
        }
        if (columns.isEmpty()) {
            PostgresSnapshotStorageAdapter.facts(
                logicalSchema,
                PostgresSnapshotStorageAdapter.COLUMNS,
                provisional = true
            )
        } else {
            PostgresSnapshotStorageAdapter.facts(logicalSchema, columns)
        }
    }

    /** The catalog type of each column of the table; none when the table does not exist. */
    private fun columns(): Map<String, String> = dataSource.autoCommitted { connection ->
        connection.prepareStatement(
            "SELECT a.attname, t.typname FROM pg_attribute a JOIN pg_type t ON t.oid = a.atttypid " +
                "WHERE a.attrelid = to_regclass(?) AND a.attnum > 0 AND NOT a.attisdropped",
        ).use { query ->
            query.setString(1, tables.snapshot)
            query.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) put(
                        rows.getString(1),
                        rows.getString(2)
                    )
                }
            }
        }
    }
}
