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

/** The failure ledger's table and statements. The column layout is part of the format ([FORMAT]). */
internal class PostgresFailureLedgerTables(val name: String) {
    init {
        require(IDENTIFIER.matches(name)) { "A ledger table name is a lower-case SQL identifier: $name." }
    }

    val table = "\"$name\""

    val ddl = """
        CREATE TABLE IF NOT EXISTS $table (
            service text COLLATE "C" NOT NULL,
            subscription text COLLATE "C" NOT NULL,
            context text COLLATE "C" NOT NULL,
            aggregate_name text COLLATE "C" NOT NULL,
            tenant_id text COLLATE "C" NOT NULL,
            aggregate_id text COLLATE "C" NOT NULL,
            from_version integer NOT NULL,
            reason text NOT NULL,
            failed_at bigint NOT NULL,
            from_position bigint,
            $RETRY_COLUMNS,
            PRIMARY KEY ($KEY)
        )
    """.trimIndent()

    /**
     * Inserts the entry, or replaces where the parked one is parked and why only when this entry's from version is
     * earlier. The conflict update locks the row and re-checks its condition against the latest committed row, so
     * concurrent parks keep the earliest. Returns a row only when the ledger changed.
     */
    val park = """
        INSERT INTO $table AS parked ($KEY, from_version, reason, failed_at, from_position, $RETRY_STATE)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT ($KEY) DO UPDATE
        SET from_version = excluded.from_version, reason = excluded.reason, failed_at = excluded.failed_at,
            from_position = excluded.from_position
        WHERE excluded.from_version < parked.from_version
        RETURNING from_version
    """.trimIndent()

    /** Replaces the parked entry's from version, reason, time and position, only when there is one. */
    val repark = "UPDATE $table SET from_version = ?, reason = ?, failed_at = ?, from_position = ? WHERE $MATCH"

    val parked = "SELECT * FROM $table WHERE $MATCH"

    val listFirst = "SELECT * FROM $table WHERE service = ? AND subscription = ? ORDER BY $ORDER LIMIT ?"

    val listAfter =
        "SELECT * FROM $table WHERE service = ? AND subscription = ? AND ($ORDER) > (?, ?, ?, ?) ORDER BY $ORDER LIMIT ?"

    val resolve = "DELETE FROM $table WHERE $MATCH RETURNING *"

    /** Claims a retry: the value is the start, then the key, then the retries read. */
    val claimRetry = "UPDATE $table SET retries = retries + 1, retry_at = ? WHERE $MATCH AND retries = ? RETURNING *"

    val markRecoverable = "UPDATE $table SET recoverable = ? WHERE $MATCH RETURNING *"

    val applyRetrySpec = """
        UPDATE $table SET retry_max_retries = ?, retry_initial_backoff_millis = ?, retry_execution_timeout_millis = ?
        WHERE $MATCH RETURNING *
    """.trimIndent()

    val request = "UPDATE $table SET request = ? WHERE $MATCH RETURNING *"

    /** Takes a request: the key, then the request read. */
    val takeRequest = "UPDATE $table SET request = NULL WHERE $MATCH AND request = ? RETURNING *"

    companion object {
        /** The layout version of the table (decision D59); format 1 keys entries by service (finding A2-13). */
        const val FORMAT = 1

        /**
         * The retry state (roadmap P.1): the automatic retries made and the start of the last one; the retry spec an
         * operator applied (null for the subscription's), its durations in milliseconds; what an operator marked it
         * and the request left for the owner of its partition (decision D141), by their names.
         */
        private val RETRY_COLUMNS = """
            retries integer NOT NULL DEFAULT 0,
            retry_at bigint,
            retry_max_retries integer,
            retry_initial_backoff_millis bigint,
            retry_execution_timeout_millis bigint,
            recoverable text NOT NULL DEFAULT 'UNKNOWN',
            request text
        """.trimIndent()
        private const val RETRY_STATE = "retries, retry_at, retry_max_retries, retry_initial_backoff_millis, " +
            "retry_execution_timeout_millis, recoverable, request"
        private val IDENTIFIER = Regex("[a-z_][a-z0-9_]{0,62}")
        private const val ORDER = "context, aggregate_name, tenant_id, aggregate_id"
        private const val KEY = "service, subscription, $ORDER"
        private const val MATCH = "service = ? AND subscription = ? AND context = ? AND aggregate_name = ? AND " +
            "tenant_id = ? AND aggregate_id = ?"
    }
}
