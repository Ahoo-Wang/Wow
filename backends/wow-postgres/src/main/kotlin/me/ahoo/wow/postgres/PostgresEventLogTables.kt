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

/**
 * The tables and statements of one context's log. Names derive from the context and are part of the format, whose
 * version is [FORMAT] (decision D59).
 *
 * A stream's facts take one column each, so the log can be queried in place; the scope is two nullable columns and
 * the causation two more, both null or both set. The transport is its own nullable `jsonb` column (decision D25):
 * only the tail reads it.
 */
internal class PostgresEventLogTables(context: String) {
    val base = "wow_log_" + context.lowercase().replace(Regex("[^a-z0-9_]"), "_")
    val log = "\"$base\""
    val partitions = "\"${base}_partition\""
    val versionKey = "${base}_version_key"
    val requestKey = "${base}_request_key"

    fun ddl(): List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $partitions (
            partition integer PRIMARY KEY,
            sequence bigint NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $log (
            partition integer NOT NULL,
            sequence bigint NOT NULL,
            aggregate_name text NOT NULL,
            tenant_id text NOT NULL,
            aggregate_id text NOT NULL,
            version integer NOT NULL,
            command_id text NOT NULL,
            request_id text NOT NULL,
            command_type text NOT NULL,
            commit_time bigint NOT NULL,
            events jsonb NOT NULL,
            owner_id text,
            space_id text,
            correlation_id text,
            causation_id text,
            headers jsonb NOT NULL,
            transport jsonb,
            PRIMARY KEY (partition, sequence),
            CONSTRAINT "$versionKey" UNIQUE (aggregate_id, tenant_id, aggregate_name, version),
            CONSTRAINT "$requestKey" UNIQUE (tenant_id, request_id)
        )
        """.trimIndent(),
    )

    /** Creates the partition counters of an empty counter table; a table that has counters keeps them. */
    fun initializePartitions(partitionCount: Int) =
        "INSERT INTO $partitions (partition, sequence) SELECT p, 0 FROM generate_series(0, ${partitionCount - 1}) AS p " +
            "WHERE NOT EXISTS (SELECT 1 FROM $partitions) ON CONFLICT (partition) DO NOTHING"

    /**
     * The whole append as one autocommitted statement, so it costs one round trip and holds the partition row's lock
     * only until that statement commits:
     *
     * - `current` reads the aggregate's version;
     * - `bump` takes the partition's next sequence (locking its row), but only when the aggregate is exactly one
     *   version behind, so a version gap neither bumps nor inserts;
     * - the insert uses that sequence. A concurrent append of the same version passes `bump` too (its subquery reads
     *   the statement's snapshot) and then hits the unique version key, which rolls the whole statement back, the
     *   bump included: no sequence is ever skipped.
     *
     * Returns the sequence, or no row when the version did not fit.
     */
    val append = """
        WITH current AS (
            SELECT coalesce(max(version), 0) AS version FROM $log
            WHERE aggregate_id = ? AND tenant_id = ? AND aggregate_name = ?
        ), bump AS (
            UPDATE $partitions SET sequence = sequence + 1
            WHERE partition = ? AND (SELECT version FROM current) = ?
            RETURNING sequence
        )
        INSERT INTO $log (partition, sequence, aggregate_name, tenant_id, aggregate_id, version, command_id, request_id,
            command_type, commit_time, events, owner_id, space_id, correlation_id, causation_id, headers, transport)
        SELECT ?, bump.sequence, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?::jsonb FROM bump
        RETURNING sequence
    """.trimIndent()

    val currentVersion =
        "SELECT coalesce(max(version), 0) FROM $log WHERE aggregate_id = ? AND tenant_id = ? AND aggregate_name = ?"

    /** The facts of a stream: every column but the transport. */
    private val facts = "aggregate_name, tenant_id, aggregate_id, version, command_id, request_id, command_type, " +
        "commit_time, events, owner_id, space_id, correlation_id, causation_id, headers"

    val read = "SELECT $facts FROM $log WHERE aggregate_id = ? AND tenant_id = ? AND aggregate_name = ? " +
        "AND version BETWEEN ? AND ? ORDER BY version"

    val byRequest = "SELECT $facts FROM $log WHERE tenant_id = ? AND request_id = ?"

    val tail = "SELECT sequence, $facts, transport FROM $log WHERE partition = ? AND sequence > ? " +
        "ORDER BY sequence LIMIT ?"

    /** A partition's counter is the sequence of its last committed entry: the bump commits with the insert. */
    val head = "SELECT sequence FROM $partitions WHERE partition = ?"

    companion object {
        /** The version of this layout. */
        const val FORMAT = 1
    }
}
