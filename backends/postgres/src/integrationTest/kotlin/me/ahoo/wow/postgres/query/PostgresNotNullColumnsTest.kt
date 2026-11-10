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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.postgres.PostgresEventLogTables
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

/**
 * The fields a query table's expressions say hold no `NULL` ([PostgresQueryExpressions.notNullFields]) are `NOT NULL`
 * columns of the tables the stores create, as the catalog has them. The compiler orders such a column plainly and bounds
 * a descending keyset by it: were one nullable, its `NULL`s would sort first descending and the bound would drop them.
 */
class PostgresNotNullColumnsTest {
    @Test
    fun `every field the query expressions take as not null is a NOT NULL column of its table`(): Unit = runBlocking {
        val context = PostgresFixture.newContext("notnull")
        val type = AggregateType(context, "order")
        PostgresFixture.snapshotStore(context).createTable(type.name)
        PostgresEventLog(PostgresFixture.dataSource, context, 1).also { it.createSchema() }.append(
            EventStreamRecord(
                ref = type.ref("t", "o-1"),
                version = 1,
                commandId = "command",
                requestId = "request",
                commandType = "$context.order.Create",
                commitTime = 1L,
                events = listOf(EventRecord("$context.order.Created", 1, "{}")),
                scope = Scope.NONE,
            ),
        )
        listOf(
            PostgresSnapshotTables(context, type.name).base to PostgresSnapshotQueryExpressions,
            PostgresEventLogTables(context).aggregate(type.name).base to PostgresEventQueryExpressions,
        ).forEach { (table, expressions) ->
            val notNull = notNullColumns(table)
            notNull.assert().describedAs("NOT NULL columns of $table").isNotEmpty()
            expressions.notNullFields.assert().isNotEmpty()
            expressions.notNullFields.forEach { field ->
                val column = expressions.COLUMNS.getValue(field)
                (column in notNull).assert().describedAs("$table.$column ($field) is NOT NULL").isTrue()
            }
        }
    }

    /** The columns of [table] in the current schema that the catalog marks `NOT NULL`. */
    private fun notNullColumns(table: String): Set<String> = PostgresFixture.dataSource.connection.use { connection ->
        connection.prepareStatement(NOT_NULL_COLUMNS).use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toSet()
            }
        }
    }

    private companion object {
        val NOT_NULL_COLUMNS = """
            SELECT a.attname
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = current_schema()
            WHERE c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped AND a.attnotnull
        """.trimIndent()
    }
}
