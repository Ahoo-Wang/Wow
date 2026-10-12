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

package me.ahoo.wow.postgres.reservation

/** The reservations' table and statements. The column layout is part of the format. */
internal class PostgresReservationTables(name: String) {
    init {
        require(IDENTIFIER.matches(name)) { "A reservation table name is a lower-case SQL identifier: $name." }
    }

    val table = "\"$name\""

    /** A null `expires_at` marks a confirmed reservation; a pending one expires at that epoch millisecond. */
    val ddl = """
        CREATE TABLE IF NOT EXISTS $table (
            namespace text NOT NULL,
            key text NOT NULL,
            owner text NOT NULL,
            expires_at bigint,
            PRIMARY KEY (namespace, key)
        )
    """.trimIndent()

    /**
     * Takes the key, or renews the owner's own reservation, in one statement. The conflict update locks the row and
     * re-checks its condition against the latest committed row, so of concurrent reservations exactly one takes a
     * free key. The condition admits the key's owner or an expired pending reservation; a confirmed one (null
     * expiry) never passes `expires_at <= now`. Returns a row only when [owner] now holds the key.
     *
     * Parameters: namespace, key, owner, expiry, now.
     */
    val reserve = """
        INSERT INTO $table AS held (namespace, key, owner, expires_at) VALUES (?, ?, ?, ?)
        ON CONFLICT (namespace, key) DO UPDATE
        SET owner = excluded.owner,
            expires_at = CASE
                WHEN held.owner = excluded.owner AND held.expires_at IS NULL THEN NULL
                ELSE excluded.expires_at
            END
        WHERE held.owner = excluded.owner OR held.expires_at <= ?
        RETURNING owner, expires_at
    """.trimIndent()

    val confirm = "UPDATE $table SET expires_at = NULL WHERE namespace = ? AND key = ? AND owner = ?"

    val release = "DELETE FROM $table WHERE namespace = ? AND key = ? AND owner = ?"

    val find = "SELECT owner, expires_at FROM $table WHERE namespace = ? AND key = ? " +
        "AND (expires_at IS NULL OR expires_at > ?)"

    private companion object {
        val IDENTIFIER = Regex("[a-z_][a-z0-9_]{0,62}")
    }
}
