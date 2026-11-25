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

package me.ahoo.wow.postgres

import java.sql.Connection

/**
 * The keys and indexes of a store's tables as its schema statements declare them, checked against the catalog: what a
 * table must have besides its format record for this version of Wow to read and write it safely.
 *
 * A table's unique keys (its `PRIMARY KEY`, a column's `PRIMARY KEY` or `UNIQUE`, a `[CONSTRAINT …] UNIQUE (…)`) are
 * what the stores rely on to refuse what must not be written twice: a log's version key refuses a second commit of
 * one version, its request key a second commit of one request. `CREATE TABLE IF NOT EXISTS` leaves a table that exists
 * as it is, so a key dropped since, or never made by a schema written by hand, would pass unseen; [check] refuses it.
 * A key holds when a valid unique index of the table, without a predicate or an expression, covers exactly its columns,
 * in any order (PostgreSQL makes one for each such constraint). An index a `CREATE INDEX` statement makes must exist by
 * its name: `auto` makes a missing one, and `validate` refuses it. A unique key is declared in its table's
 * `CREATE TABLE`, where it is checked, never as a `CREATE UNIQUE INDEX` of its own.
 */
internal object PostgresKeys {
    /** A unique key of [table] over [columns], as its statement wrote it ([declared]). */
    data class Key(val table: String, val columns: Set<String>, val declared: String)

    /** An index [name] of [table], as its [statement] makes it. */
    data class Index(val name: String, val table: String, val statement: String)

    private val CREATE_TABLE =
        Regex("""^\s*CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+("[^"]+"|[\w.]+)\s*\(""", RegexOption.IGNORE_CASE)
    private val TABLE_KEY = Regex(
        """^(?:CONSTRAINT\s+(?:"[^"]+"|\w+)\s+)?(?:PRIMARY\s+KEY|UNIQUE)\s*\(([^)]*)\)""",
        RegexOption.IGNORE_CASE,
    )
    private val COLUMN_KEY = Regex("""\b(?:PRIMARY\s+KEY|UNIQUE)\b""", RegexOption.IGNORE_CASE)

    /** The unique keys the `CREATE TABLE` statements of [statements] declare. */
    fun keys(statements: List<String>): List<Key> = statements.flatMap { statement ->
        val table = CREATE_TABLE.find(statement)?.groupValues?.get(1) ?: return@flatMap emptyList()
        val body = statement.substring(statement.indexOf('(', statement.indexOf(table) + table.length) + 1)
            .substringBeforeLast(')')
        elements(body).mapNotNull { element -> keyOf(table, element) }
    }

    /** The indexes the `CREATE INDEX` statements of [statements] make. */
    fun indexes(statements: List<String>): List<Index> = statements.mapNotNull { statement ->
        if (CREATE_TABLE.containsMatchIn(statement)) {
            return@mapNotNull null
        }
        val target = PostgresSchema.target(statement)
        Index(target.name, target.table, statement.trim())
    }

    /**
     * Refuses, naming the table and the fix, a table of [statements] without one of the unique keys they declare (or
     * that does not exist: a table without its format record, which its format check does not cover), and an index they
     * make that does not exist.
     */
    fun check(connection: Connection, statements: List<String>) {
        keys(statements).groupBy { it.table }.forEach { (table, keys) ->
            val unique = uniqueIndexes(connection, table)
            keys.firstOrNull { it.columns !in unique }?.let { key ->
                error(
                    "${unquoted(table)} has no unique key on (${key.columns.joinToString()}), or does not exist; this " +
                        "version of Wow declares it (${key.declared}) and relies on it to refuse a duplicate write. " +
                        "Restore it as Wow's schema creates it, or start from an empty store.",
                )
            }
        }
        indexes(statements).forEach { index ->
            check(PostgresSchema.exists(connection, index.name)) {
                "${unquoted(index.name)} of ${unquoted(index.table)} does not exist: create it as Wow's schema does " +
                    "(${index.statement}), or let ddl auto create it."
            }
        }
    }

    /** The column sets of [table]'s valid unique indexes that have no predicate and no expression. */
    private fun uniqueIndexes(connection: Connection, table: String): Set<Set<String>> =
        connection.prepareStatement(
            """
            SELECT array(SELECT a.attname FROM pg_attribute a WHERE a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey))
            FROM pg_index i
            WHERE i.indrelid = to_regclass(?) AND i.indisunique AND i.indisvalid
                AND i.indpred IS NULL AND i.indexprs IS NULL
            """.trimIndent(),
        ).use { query ->
            query.setString(1, table)
            query.executeQuery().use { rows ->
                buildSet {
                    while (rows.next()) {
                        @Suppress("UNCHECKED_CAST")
                        add((rows.getArray(1).array as Array<String>).toSet())
                    }
                }
            }
        }

    /** The unique key [element] of a `CREATE TABLE`'s body declares, a table constraint or a column's; null for none. */
    private fun keyOf(table: String, element: String): Key? {
        TABLE_KEY.find(element)?.let { key ->
            val columns = key.groupValues[1].split(',').map(::column).toSet()
            return Key(table, columns, element)
        }
        if (element.startsWith("CONSTRAINT", ignoreCase = true) || !COLUMN_KEY.containsMatchIn(element)) {
            return null
        }
        return Key(table, setOf(column(element.substringBefore(' '))), element)
    }

    /** [body]'s elements, split at the commas outside parentheses. */
    private fun elements(body: String): List<String> = buildList {
        var depth = 0
        val element = StringBuilder()
        body.forEach { char ->
            when {
                char == '(' -> depth++
                char == ')' -> depth--
            }
            if (char == ',' && depth == 0) {
                add(element.toString().trim())
                element.clear()
            } else {
                element.append(char)
            }
        }
        add(element.toString().trim())
    }

    /** A column as the catalog names it: unquoted, a name written without quotes in lower case. */
    private fun column(text: String): String {
        val name = text.trim()
        return if (name.startsWith('"')) name.removeSurrounding("\"") else name.lowercase()
    }

    private fun unquoted(name: String): String = name.removeSurrounding("\"")
}
