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
 * The format versions of stored structures (decision D59): one row per structure (a log or a snapshot table) in
 * [TABLE], recorded by the table, not by each row. `createSchema` claims its structure's version before creating it:
 * the first claim records it, a structure recorded under another version is refused, and so is a table that exists
 * without a record (an earlier, incompatible build created it), so this version of Wow never reads or writes a layout
 * it does not know. An incompatible layout changes only in a major version, through the migration tool
 * (D22); adding an optional column with a default does not change the version.
 */
object PostgresFormat {
    /** The table of format versions, shared by every structure in the database. */
    const val TABLE = "wow_format"

    /**
     * Records [structure], the table about to be created, at [version] when it has no version yet. Fails when it is
     * recorded at another version, or when the table already exists without a record.
     */
    fun claim(connection: Connection, structure: String, version: Int) {
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE IF NOT EXISTS $TABLE (structure text PRIMARY KEY, version integer NOT NULL)",
            )
        }
        val stored = recorded(connection, structure) ?: run {
            // The record is written before the table is created, so a table without one predates format records.
            check(!tableExists(connection, structure)) {
                "$structure exists without a format record: an earlier, incompatible build created it. Drop it."
            }
            connection.prepareStatement(
                "INSERT INTO $TABLE (structure, version) VALUES (?, ?) ON CONFLICT (structure) DO NOTHING",
            ).use { insert ->
                insert.setString(1, structure)
                insert.setInt(2, version)
                insert.executeUpdate()
            }
            checkNotNull(recorded(connection, structure))
        }
        check(stored == version) {
            "$structure is stored in format $stored; this version of Wow reads format $version. " +
                "Migrate it with the migration tool rather than opening it."
        }
    }

    /**
     * Runs [block], which claims and creates structures, in one transaction under an advisory lock, so that nodes and
     * threads creating one structure at once neither race its format record nor `CREATE TABLE IF NOT EXISTS` (which
     * can fail on a concurrent create). Rolls back whatever [block] did when it fails.
     */
    internal fun <T> creating(connection: Connection, block: () -> T): T {
        connection.autoCommit = false
        try {
            connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { lock ->
                lock.setLong(1, CREATION_LOCK)
                lock.executeQuery().close()
            }
            return block().also { connection.commit() }
        } catch (
            @Suppress("TooGenericExceptionCaught") // Rolls back whatever failed, then rethrows it unchanged.
            failure: Throwable,
        ) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    /** The key of the advisory lock [creating] takes. */
    private const val CREATION_LOCK = 0x776f775f666d74L

    private fun recorded(connection: Connection, structure: String): Int? =
        connection.prepareStatement("SELECT version FROM $TABLE WHERE structure = ?").use { query ->
            query.setString(1, structure)
            query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
        }

    private fun tableExists(connection: Connection, table: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ?",
        ).use { query ->
            query.setString(1, table)
            query.executeQuery().use { it.next() }
        }
}
