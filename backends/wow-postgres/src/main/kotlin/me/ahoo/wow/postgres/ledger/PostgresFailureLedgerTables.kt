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

package me.ahoo.wow.postgres.ledger

/** The failure ledger's table and statements. The column layout is part of the format. */
internal class PostgresFailureLedgerTables(name: String) {
    init {
        require(IDENTIFIER.matches(name)) { "A ledger table name is a lower-case SQL identifier: $name." }
    }

    val table = "\"$name\""

    val ddl = """
        CREATE TABLE IF NOT EXISTS $table (
            subscription text COLLATE "C" NOT NULL,
            context text COLLATE "C" NOT NULL,
            aggregate_name text COLLATE "C" NOT NULL,
            tenant_id text COLLATE "C" NOT NULL,
            aggregate_id text COLLATE "C" NOT NULL,
            from_version integer NOT NULL,
            reason text NOT NULL,
            failed_at bigint NOT NULL,
            PRIMARY KEY ($KEY)
        )
    """.trimIndent()

    /**
     * Inserts the entry, or replaces the parked one only when this entry's from version is earlier. The conflict
     * update locks the row and re-checks its condition against the latest committed row, so concurrent parks keep
     * the earliest. Returns a row only when the ledger changed.
     */
    val park = """
        INSERT INTO $table AS parked ($KEY, from_version, reason, failed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT ($KEY) DO UPDATE
        SET from_version = excluded.from_version, reason = excluded.reason, failed_at = excluded.failed_at
        WHERE excluded.from_version < parked.from_version
        RETURNING from_version
    """.trimIndent()

    val parked = "SELECT * FROM $table WHERE $MATCH"

    val listFirst = "SELECT * FROM $table WHERE subscription = ? ORDER BY $ORDER LIMIT ?"

    val listAfter = "SELECT * FROM $table WHERE subscription = ? AND ($ORDER) > (?, ?, ?, ?) ORDER BY $ORDER LIMIT ?"

    val resolve = "DELETE FROM $table WHERE $MATCH RETURNING *"

    private companion object {
        val IDENTIFIER = Regex("[a-z_][a-z0-9_]{0,62}")
        const val ORDER = "context, aggregate_name, tenant_id, aggregate_id"
        const val KEY = "subscription, $ORDER"
        const val MATCH =
            "subscription = ? AND context = ? AND aggregate_name = ? AND tenant_id = ? AND aggregate_id = ?"
    }
}
