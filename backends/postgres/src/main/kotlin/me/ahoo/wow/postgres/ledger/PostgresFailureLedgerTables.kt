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
            subscription_context text COLLATE "C" NOT NULL,
            subscription text COLLATE "C" NOT NULL,
            context text COLLATE "C" NOT NULL,
            aggregate_name text COLLATE "C" NOT NULL,
            tenant_id text COLLATE "C" NOT NULL,
            aggregate_id text COLLATE "C" NOT NULL,
            partition integer NOT NULL,
            from_version integer NOT NULL,
            reason text NOT NULL,
            failed_at bigint NOT NULL,
            from_position bigint,
            $RETRY_COLUMNS,
            PRIMARY KEY ($KEY)
        )
    """.trimIndent()

    /**
     * The table, then its indexes: on (service, subscription, partition, next attempt), which answers what is due in
     * a node's partitions ([dueFirst]), and on (service, subscription, tenant, then the key), which answers a tenant's
     * listing in its order ([listTenantFirst]), a subscription being its context and name. The primary key answers
     * [count] and the other listings.
     */
    val ddls = listOf(
        ddl,
        "CREATE INDEX IF NOT EXISTS \"${name}_due\" ON $table ($SUBSCRIPTION_COLUMNS, partition, next_attempt_at)",
        "CREATE INDEX IF NOT EXISTS \"${name}_tenant\" ON $table ($SUBSCRIPTION_COLUMNS, tenant_id, $ORDER)",
    )

    /**
     * Inserts the entry (at revision 0, the column's default), or replaces where the parked one is parked and why
     * only when this entry's from version is earlier, at the next revision and unscheduled. The conflict update locks the row and re-checks its condition against the latest committed row, so
     * concurrent parks keep the earliest. Returns a row only when the ledger changed.
     */
    val park = """
        INSERT INTO $table AS parked ($KEY, partition, from_version, reason, failed_at, from_position, $RETRY_STATE)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT ($KEY) DO UPDATE
        SET partition = excluded.partition, from_version = excluded.from_version, reason = excluded.reason,
            failed_at = excluded.failed_at, from_position = excluded.from_position, $UNSCHEDULED,
            revision = parked.revision + 1
        WHERE excluded.from_version < parked.from_version
        RETURNING from_version
    """.trimIndent()

    /**
     * Replaces the parked entry's partition, from version, reason, time and position, only while it is parked in the
     * partition given last, at the next revision and unscheduled.
     */
    val repark = "UPDATE $table SET partition = ?, from_version = ?, reason = ?, failed_at = ?, from_position = ?, " +
        "$UNSCHEDULED, $NEXT_REVISION WHERE $MATCH AND partition = ?"

    val parked = "SELECT * FROM $table WHERE $MATCH"

    val listFirst = "SELECT * FROM $table WHERE $OF_SUBSCRIPTION ORDER BY $ORDER LIMIT ?"

    val listAfter =
        "SELECT * FROM $table WHERE $OF_SUBSCRIPTION AND ($ORDER) > (?, ?, ?, ?) ORDER BY $ORDER LIMIT ?"

    /** A tenant's first page: the service, the subscription's context and name, the tenant, the limit. */
    val listTenantFirst =
        "SELECT * FROM $table WHERE $OF_SUBSCRIPTION AND tenant_id = ? ORDER BY $ORDER LIMIT ?"

    /** A tenant's next page: the key read last, the tenant, the limit. */
    val listTenantAfter = "SELECT * FROM $table WHERE $OF_SUBSCRIPTION AND ($ORDER) > (?, ?, ?, ?) " +
        "AND tenant_id = ? ORDER BY $ORDER LIMIT ?"

    /** What is due: the service, the subscription (context, name), the partitions (an array), the time, the limit. */
    val dueFirst = "SELECT * FROM $table WHERE $OF_SUBSCRIPTION AND $DUE ORDER BY $ORDER LIMIT ?"

    /** What is due after a key: the key read last, the partitions, the time, the limit. */
    val dueAfter = "SELECT * FROM $table WHERE $OF_SUBSCRIPTION AND ($ORDER) > (?, ?, ?, ?) " +
        "AND $DUE ORDER BY $ORDER LIMIT ?"

    /** Counts up to a bound: the service, the subscription's context and name, the bound. */
    val count = "SELECT count(*) FROM (SELECT 1 FROM $table WHERE $OF_SUBSCRIPTION LIMIT ?) AS parked"

    val resolve = "DELETE FROM $table WHERE $MATCH RETURNING *"

    /** Resolves an entry while it is parked in a partition: the key, then the partition. */
    val resolveIn = "DELETE FROM $table WHERE $MATCH AND partition = ? RETURNING *"

    /** Claims a retry: the value is the start, then the key, then the revision read. */
    val claimRetry = "UPDATE $table SET retries = retries + 1, retry_at = ?, $UNSCHEDULED, $NEXT_REVISION " +
        "WHERE $MATCH AND revision = ? RETURNING *"

    val markRecoverable = "UPDATE $table SET recoverable = ?, $UNSCHEDULED, $NEXT_REVISION WHERE $MATCH RETURNING *"

    val applyRetrySpec = """
        UPDATE $table SET retry_max_retries = ?, retry_initial_backoff_millis = ?, retry_claim_ttl_millis = ?,
            $UNSCHEDULED, $NEXT_REVISION
        WHERE $MATCH RETURNING *
    """.trimIndent()

    val request = "UPDATE $table SET request = ?, $UNSCHEDULED, $NEXT_REVISION WHERE $MATCH RETURNING *"

    /** Schedules the next attempt: the time, then the key, then the revision read. */
    val schedule = "UPDATE $table SET next_attempt_at = ?, $NEXT_REVISION WHERE $MATCH AND revision = ? RETURNING *"

    /**
     * Restates why the entry is parked and its retry state: the reason, the failure time, the retries, the last retry's
     * start, the spec's three columns and the mark, then the key, then the revision read.
     */
    val restate = """
        UPDATE $table SET reason = ?, failed_at = ?, retries = ?, retry_at = ?, retry_max_retries = ?,
            retry_initial_backoff_millis = ?, retry_claim_ttl_millis = ?, recoverable = ?, $UNSCHEDULED, $NEXT_REVISION
        WHERE $MATCH AND revision = ? RETURNING *
    """.trimIndent()

    /** Takes a request: the key, then the revision read. */
    val takeRequest = "UPDATE $table SET request = NULL, $NEXT_REVISION WHERE $MATCH AND revision = ? RETURNING *"

    companion object {
        /**
         * The layout version of the table: format 1 keyed entries by service; format 2 adds each entry's partition
         * and next attempt, and the due and tenant indexes; format 3 names an applied spec's claim TTL
         * `retry_claim_ttl_millis` and an operator's request to retry `RETRY`, and gained the revision in place;
         * format 4 keys an entry by its subscription's context too (`subscription_context`), in the key and both
         * indexes, the revision among its columns from the start.
         */
        const val FORMAT = 4

        /**
         * The retry state: the automatic retries made and the start of the last one; the retry spec an
         * operator applied (null for the subscription's), its durations in milliseconds; what an operator marked it
         * and the request left for the owner of its partition, by their names; when that owner next looks at it; the
         * entry's revision ([me.ahoo.wow.spi.ledger.ParkedAggregate.revision]).
         */
        private val RETRY_COLUMNS = """
            retries integer NOT NULL DEFAULT 0,
            retry_at bigint,
            retry_max_retries integer,
            retry_initial_backoff_millis bigint,
            retry_claim_ttl_millis bigint,
            recoverable text NOT NULL DEFAULT 'UNKNOWN',
            request text,
            next_attempt_at bigint,
            revision bigint NOT NULL DEFAULT 0
        """.trimIndent()
        private const val RETRY_STATE = "retries, retry_at, retry_max_retries, retry_initial_backoff_millis, " +
            "retry_claim_ttl_millis, recoverable, request, next_attempt_at"
        private const val DUE = "partition = ANY (?) AND (next_attempt_at IS NULL OR next_attempt_at <= ?)"
        private const val UNSCHEDULED = "next_attempt_at = NULL"
        private const val NEXT_REVISION = "revision = revision + 1"

        private val IDENTIFIER = Regex("[a-z_][a-z0-9_]{0,62}")
        private const val ORDER = "context, aggregate_name, tenant_id, aggregate_id"
        private const val SUBSCRIPTION_COLUMNS = "service, subscription_context, subscription"
        private const val KEY = "$SUBSCRIPTION_COLUMNS, $ORDER"

        /** The entries of a service's subscription: the service, the subscription's context, its name. */
        private const val OF_SUBSCRIPTION = "service = ? AND subscription_context = ? AND subscription = ?"
        private const val MATCH = "$OF_SUBSCRIPTION AND context = ? AND aggregate_name = ? AND tenant_id = ? AND " +
            "aggregate_id = ?"
    }
}
