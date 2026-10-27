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

package me.ahoo.wow.postgres.timer

import me.ahoo.wow.postgres.PostgresIdentifiers

/**
 * The timer tables of one context (decisions D166, D198, D307): one per aggregate type, `wow_timer__<context>__<aggregate>`,
 * created on the type's first timer and recorded in a registry ([REGISTRY], shared by every context), which the reads
 * across types ([due], [parked]) union. The column layout is part of the format, version [FORMAT] (decision D59):
 * format 2 added the rule and the slot, format 3 the slot's due time (`slot_due_at`), kept apart from `due_at`, which
 * a claim or a retry moves, format 4 the owner in three columns instead of its joined name (finding A2-12), format 5
 * a table per aggregate type, its owner's aggregate the table's (decision D307). The timers are a view rebuilt from the
 * log: a table of an earlier format is refused, dropped and rebuilt (decisions D291, D306).
 *
 * One row per timer, keyed by its owner's tenant and id and `key`; `timer_id` is the timer's id, its command sent with
 * the request id `timer_id#slot`; the rule is a `jsonb` object (`{type, ...}`, as Wow's JSON writes it) and the slot a
 * counter from 1. The target takes four columns; the body is the command's JSON as text, given back as written; the
 * causation is two nullable columns, both null or both set; the operator (decision D163) is a nullable `jsonb` object,
 * `{id, tenantId, crossScope}`; the scope the command is sent in (decision D189) a nullable `jsonb` object
 * `{ownerId, spaceId}`, null for none. A partial index on `(partition, due_at, timer_id)` of the PENDING and FIRING
 * (claimed) timers serves the due read, each table's branch of it, and one on the target of the PARKED timers serves a
 * resume.
 */
internal class PostgresTimerTables(private val context: String) {
    /** The registry's statements and the registry itself. */
    fun ddl(): List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $REGISTRY (
            context text NOT NULL,
            aggregate text NOT NULL,
            timer_table text NOT NULL UNIQUE,
            PRIMARY KEY (context, aggregate)
        )
        """.trimIndent(),
    )

    /** The table of [aggregate]'s timers. */
    fun aggregate(aggregate: String): AggregateTimerTable =
        AggregateTimerTable(aggregate, PostgresIdentifiers.of(PREFIX, context, aggregate))

    /** Records a table: context, aggregate, table. */
    val register = "INSERT INTO $REGISTRY (context, aggregate, timer_table) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"

    /** Who holds a table name: context, aggregate. */
    val holder = "SELECT context, aggregate FROM $REGISTRY WHERE timer_table = ?"

    /** The aggregates of the context that have timer tables. */
    val registered = "SELECT aggregate FROM $REGISTRY WHERE context = ? ORDER BY aggregate"

    /**
     * The due timers across [tables], in one statement: how many tables the registry holds for the context (`registered`,
     * on every row, and on one row with no timer when none is due), and the first `limit` due timers, merged by due time
     * from each table's first `limit`, each table's read over its partial index. Each timer's `branch` is the index of its
     * table in [tables]. A `registered` other than the tables read tells the store to read the registry again.
     * Parameters: the context, then per table the partitions (an `integer[]`), until, limit; then limit.
     */
    fun due(tables: List<AggregateTimerTable>): String = unioned(tables) { table ->
        "SELECT $COLUMNS FROM ${table.table} WHERE partition = ANY(?) AND status IN ('PENDING', 'FIRING') " +
            "AND due_at <= ? ORDER BY due_at, timer_id LIMIT ?"
    }

    /**
     * The PARKED timers of a target across [tables], as [due] reads them. Parameters: the context, then per table the
     * target's context, aggregate, tenant and id; then the limit.
     */
    fun parked(tables: List<AggregateTimerTable>): String = unioned(tables) { table ->
        "SELECT $COLUMNS FROM ${table.table} WHERE target_context = ? AND target_aggregate = ? " +
            "AND target_tenant_id = ? AND target_id = ? AND status = 'PARKED'"
    }

    private fun unioned(tables: List<AggregateTimerTable>, branch: (AggregateTimerTable) -> String): String {
        if (tables.isEmpty()) {
            // No table to read: only the count, on one row with no timer.
            return "SELECT count(*) AS registered, NULL::integer AS branch, NULL::text AS timer_id FROM $REGISTRY " +
                "WHERE context = ?"
        }
        val branches = tables.withIndex().joinToString(" UNION ALL ") { (index, table) ->
            "(SELECT $index AS branch, * FROM (${branch(table)}) AS timers)"
        }
        return "SELECT counter.registered, timer.* FROM (SELECT count(*) AS registered FROM $REGISTRY " +
            "WHERE context = ?) AS counter LEFT JOIN LATERAL (SELECT * FROM ($branches) AS merged " +
            "ORDER BY due_at, timer_id LIMIT ?) AS timer ON true ORDER BY timer.due_at, timer.timer_id"
    }

    companion object {
        /** The version of this layout. */
        const val FORMAT = 5
        const val PREFIX = "wow_timer"

        /** The registry of timer tables, shared by every context. */
        const val REGISTRY = "wow_timer_aggregate"
        const val OWNER = "owner_tenant_id = ? AND owner_id = ?"

        const val COLUMNS = "owner_tenant_id, owner_id, key, timer_id, version, partition, rule, slot, due_at, " +
            "target_context, target_aggregate, target_tenant_id, target_id, command_type, body, correlation_id, " +
            "causation_id, headers, status, attempts, outcome, operator, scope, slot_due_at, skipped"
    }
}

/** The timer table of one aggregate type, [base] its name, and its statements by owner. */
internal class AggregateTimerTable(val aggregate: String, val base: String) {
    val table = "\"$base\""
    private val dueIndex = PostgresIdentifiers.limited("${base}_due")
    private val parkedIndex = PostgresIdentifiers.limited("${base}_parked")

    /** The index of the due timers, which each branch of the due read uses. */
    val due: String get() = dueIndex

    fun ddl(): List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $table (
            owner_tenant_id text NOT NULL,
            owner_id text NOT NULL,
            key text NOT NULL,
            timer_id text NOT NULL,
            version bigint NOT NULL,
            partition integer NOT NULL,
            rule jsonb NOT NULL,
            slot bigint NOT NULL,
            due_at bigint NOT NULL,
            slot_due_at bigint NOT NULL,
            target_context text NOT NULL,
            target_aggregate text NOT NULL,
            target_tenant_id text NOT NULL,
            target_id text NOT NULL,
            command_type text NOT NULL,
            body text NOT NULL,
            correlation_id text,
            causation_id text,
            headers jsonb NOT NULL,
            status text NOT NULL,
            attempts integer NOT NULL,
            outcome text,
            operator jsonb,
            scope jsonb,
            skipped bigint NOT NULL,
            PRIMARY KEY (owner_tenant_id, owner_id, key)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS \"$dueIndex\" ON $table (partition, due_at, timer_id) " +
            "WHERE status IN ('PENDING', 'FIRING')",
        "CREATE INDEX IF NOT EXISTS \"$parkedIndex\" ON $table " +
            "(target_context, target_aggregate, target_tenant_id, target_id) WHERE status = 'PARKED'",
    )

    /**
     * Writes a timer, PENDING, unless the row holds the same timer or a later version. The conflict update locks the
     * row and re-checks its condition against the latest committed row, so concurrent writes of one key keep the
     * latest version. Parameters: the [PostgresTimerTables.COLUMNS] up to `headers`, then the operator, the scope, the
     * slot's due time and the skipped slots.
     */
    val put = """
        INSERT INTO $table AS held (${PostgresTimerTables.COLUMNS}) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?,
            ?, ?, ?, ?, ?, ?::jsonb, 'PENDING', 0, NULL, ?::jsonb, ?::jsonb, ?, ?)
        ON CONFLICT (owner_tenant_id, owner_id, key) DO UPDATE
        SET timer_id = excluded.timer_id, version = excluded.version, partition = excluded.partition,
            rule = excluded.rule, slot = excluded.slot, due_at = excluded.due_at, target_context = excluded.target_context,
            target_aggregate = excluded.target_aggregate, target_tenant_id = excluded.target_tenant_id,
            target_id = excluded.target_id, command_type = excluded.command_type, body = excluded.body,
            correlation_id = excluded.correlation_id, causation_id = excluded.causation_id,
            headers = excluded.headers, status = 'PENDING', attempts = 0, outcome = NULL,
            operator = excluded.operator, scope = excluded.scope, slot_due_at = excluded.slot_due_at,
            skipped = excluded.skipped
        WHERE held.timer_id <> excluded.timer_id AND held.version <= excluded.version
    """.trimIndent()

    /** Parameters: version, the owner's two columns, key, version. */
    val cancel = "UPDATE $table SET status = 'CANCELLED', version = greatest(version, ?) " +
        "WHERE ${PostgresTimerTables.OWNER} AND key = ? AND status IN ('PENDING', 'FIRING', 'PARKED') AND version <= ?"

    /** Parameters: version, the owner's two columns, version. */
    val cancelAll = "UPDATE $table SET status = 'CANCELLED', version = greatest(version, ?) " +
        "WHERE ${PostgresTimerTables.OWNER} AND status IN ('PENDING', 'FIRING', 'PARKED') AND version <= ?"

    /** Parameters: the owner's two columns, key. */
    val find = "SELECT ${PostgresTimerTables.COLUMNS} FROM $table WHERE ${PostgresTimerTables.OWNER} AND key = ?"

    /**
     * Parameters: the next status, slot, slot's due at, skipped slots, due at, attempts and outcome; then the owner's
     * two columns, key, timer id, status, slot, slot's due at, attempts and due at the row must still hold.
     */
    val settle = "UPDATE $table SET status = ?, slot = ?, slot_due_at = ?, skipped = ?, due_at = ?, attempts = ?, " +
        "outcome = ? " +
        "WHERE ${PostgresTimerTables.OWNER} AND key = ? AND timer_id = ? AND status = ? AND slot = ? " +
        "AND slot_due_at = ? AND attempts = ? AND due_at = ?"
}
