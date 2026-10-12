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
 * [TABLE], recorded by the table, not by each row. `createSchema` claims its structure's version: the first claim
 * records it, and a structure recorded under another version is refused, so this version of Wow never reads or writes
 * a layout it does not know. An incompatible layout changes only in a major version, through the migration tool
 * (D22); adding an optional column with a default does not change the version.
 */
object PostgresFormat {
    /** The table of format versions, shared by every structure in the database. */
    const val TABLE = "wow_format"

    /** Records [structure] at [version] when it has no version yet; fails when it has another one. */
    fun claim(connection: Connection, structure: String, version: Int) {
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE IF NOT EXISTS $TABLE (structure text PRIMARY KEY, version integer NOT NULL)",
            )
        }
        connection.prepareStatement(
            "INSERT INTO $TABLE (structure, version) VALUES (?, ?) ON CONFLICT (structure) DO NOTHING",
        ).use { insert ->
            insert.setString(1, structure)
            insert.setInt(2, version)
            insert.executeUpdate()
        }
        val stored = connection.prepareStatement("SELECT version FROM $TABLE WHERE structure = ?").use { query ->
            query.setString(1, structure)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
        check(stored == version) {
            "$structure is stored in format $stored; this version of Wow reads format $version. " +
                "Migrate it with the migration tool rather than opening it."
        }
    }
}
