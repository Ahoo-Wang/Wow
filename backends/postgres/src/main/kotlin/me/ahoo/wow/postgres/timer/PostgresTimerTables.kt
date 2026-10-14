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
 * The timer table of one context, `wow_timer__<context>` (decisions D166, D198), and its statements. The column layout
 * is part of the format, version [FORMAT] (decision D59): format 2 added the rule and the slot, format 3 the slot's due
 * time (`slot_due_at`), kept apart from `due_at`, which a claim or a retry moves.
 *
 * One row per timer, keyed by `(owner, key)`; `timer_id` is the timer's id, its command sent with the request id
 * `timer_id#slot`; the rule is a `jsonb` object (`{type, ...}`, as Wow's JSON writes it) and the slot a counter from 1.
 * The target takes four columns; the body is the command's JSON as text, given back as written; the causation is two
 * nullable columns, both null or both set; the operator (decision D163) is a nullable `jsonb` object, `{id, tenantId,
 * crossScope}`, a column added within format 1 that a table created before it gets on its next ensure; so is the
 * scope the command is sent in (decision D189), a nullable `jsonb` object `{ownerId, spaceId}`, null for none. A
 * partial index on `(partition, due_at, timer_id)` of the PENDING and FIRING (claimed) timers
 * serves the runner's due read, and one on the target of the PARKED timers serves a resume.
 */
internal class PostgresTimerTables(context: String) {
    val base = PostgresIdentifiers.limited("${PREFIX}__${PostgresIdentifiers.part(context)}")
    val table = "\"$base\""
    private val dueIndex = PostgresIdentifiers.limited("${base}_due")
    private val parkedIndex = PostgresIdentifiers.limited("${base}_parked")

    fun ddl(): List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $table (
            owner text NOT NULL,
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
            skipped bigint NOT NULL DEFAULT 0,
            PRIMARY KEY (owner, key)
        )
        """.trimIndent(),
        // Added within format 3 (decision D198): a table created before it gets it on its next ensure.
        "ALTER TABLE $table ADD COLUMN IF NOT EXISTS skipped bigint NOT NULL DEFAULT 0",
        "CREATE INDEX IF NOT EXISTS \"$dueIndex\" ON $table (partition, due_at, timer_id) " +
            "WHERE status IN ('PENDING', 'FIRING')",
        "CREATE INDEX IF NOT EXISTS \"$parkedIndex\" ON $table " +
            "(target_context, target_aggregate, target_tenant_id, target_id) WHERE status = 'PARKED'",
    )

    /**
     * Writes a timer, PENDING, unless the row holds the same timer or a later version. The conflict update locks the
     * row and re-checks its condition against the latest committed row, so concurrent writes of one key keep the
     * latest version. Parameters: the [COLUMNS] up to `headers`, then the operator, the scope, the slot's due time and
     * the skipped slots.
     */
    val put = """
        INSERT INTO $table AS held ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
            'PENDING', 0, NULL, ?::jsonb, ?::jsonb, ?, ?)
        ON CONFLICT (owner, key) DO UPDATE
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

    /** Parameters: version, owner, key, version. */
    val cancel = "UPDATE $table SET status = 'CANCELLED', version = greatest(version, ?) " +
        "WHERE owner = ? AND key = ? AND status IN ('PENDING', 'FIRING', 'PARKED') AND version <= ?"

    /** Parameters: version, owner, version. */
    val cancelAll = "UPDATE $table SET status = 'CANCELLED', version = greatest(version, ?) " +
        "WHERE owner = ? AND status IN ('PENDING', 'FIRING', 'PARKED') AND version <= ?"

    val find = "SELECT $COLUMNS FROM $table WHERE owner = ? AND key = ?"

    /** Parameters: the partitions (an `integer[]`), until, limit. */
    val due = "SELECT $COLUMNS FROM $table WHERE partition = ANY(?) AND status IN ('PENDING', 'FIRING') " +
        "AND due_at <= ? " +
        "ORDER BY due_at, timer_id LIMIT ?"

    /**
     * Parameters: the next status, slot, slot's due at, skipped slots, due at, attempts and outcome; then the owner, key,
     * timer id, status, slot, slot's due at, attempts and due at the row must still hold.
     */
    val settle = "UPDATE $table SET status = ?, slot = ?, slot_due_at = ?, skipped = ?, due_at = ?, attempts = ?, " +
        "outcome = ? " +
        "WHERE owner = ? AND key = ? AND timer_id = ? AND status = ? AND slot = ? AND slot_due_at = ? " +
        "AND attempts = ? AND due_at = ?"

    /** Parameters: the target's context, aggregate, tenant and id. */
    val parked = "SELECT $COLUMNS FROM $table WHERE target_context = ? AND target_aggregate = ? " +
        "AND target_tenant_id = ? AND target_id = ? AND status = 'PARKED' ORDER BY due_at, timer_id"

    companion object {
        /** The version of this layout. */
        const val FORMAT = 3
        const val PREFIX = "wow_timer"

        const val COLUMNS = "owner, key, timer_id, version, partition, rule, slot, due_at, target_context, target_aggregate, " +
            "target_tenant_id, target_id, command_type, body, correlation_id, causation_id, headers, status, " +
            "attempts, outcome, operator, scope, slot_due_at, skipped"
    }
}
