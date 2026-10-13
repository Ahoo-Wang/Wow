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
 * The tables and statements of one context's log (decision D162). Names derive from the context and the aggregate
 * types (rule of D161, [PostgresNames]) and are part of the format, whose version is [FORMAT] (decision D59):
 *
 * - `wow_log__<context>__partition`: one counter row per partition, the context's;
 * - `wow_log__<context>__<aggregate>`: the streams of one aggregate type ([AggregateLogTable]);
 * - [REGISTRY], shared by every context: which aggregate tables each context's log has, so that a tail finds them all.
 */
internal class PostgresEventLogTables(val context: String) {
    val partitionsBase = PostgresIdentifiers.of(PREFIX, context, PARTITION)
    val partitions = "\"$partitionsBase\""

    fun ddl(): List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $partitions (
            partition integer PRIMARY KEY,
            sequence bigint NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $REGISTRY (
            context text NOT NULL,
            aggregate text NOT NULL,
            log_table text NOT NULL UNIQUE,
            PRIMARY KEY (context, aggregate)
        )
        """.trimIndent(),
    )

    /** Creates the partition counters of an empty counter table; a table that has counters keeps them. */
    fun initializePartitions(partitionCount: Int) =
        "INSERT INTO $partitions (partition, sequence) SELECT p, 0 FROM generate_series(0, ${partitionCount - 1}) AS p " +
            "WHERE NOT EXISTS (SELECT 1 FROM $partitions) ON CONFLICT (partition) DO NOTHING"

    /** The table of [aggregate]; refuses a name that takes the counter table's. */
    fun aggregate(aggregate: String): AggregateLogTable {
        val table = AggregateLogTable(aggregate, PostgresIdentifiers.of(PREFIX, context, aggregate), partitions)
        require(table.base != partitionsBase) {
            "The aggregate $aggregate of $context would take the name of the log's partition counters."
        }
        return table
    }

    /** Records an aggregate table: context, aggregate, table. An existing record is kept. */
    val register = "INSERT INTO $REGISTRY (context, aggregate, log_table) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"

    /** Who holds a table name: context, aggregate. */
    val holder = "SELECT context, aggregate FROM $REGISTRY WHERE log_table = ?"

    /** The aggregates of the context's log. */
    val registered = "SELECT aggregate FROM $REGISTRY WHERE context = ? ORDER BY aggregate"

    /**
     * The tail of a partition across [tables], in one statement and so one snapshot: the partition's counter (`head`,
     * on every row, and on one row with no entry when there is none) and the first `limit` entries after a sequence,
     * merged by sequence from each table's first `limit`. Each entry's `branch` is the index of its table in [tables].
     * Because a counter bump commits with its insert and holds the counter row until then, a snapshot that sees the
     * counter at `head` sees every entry up to it: fewer entries than `head` calls for means a table missing from
     * [tables]. Parameters: partition, then per table partition, after, limit, then limit.
     */
    fun tail(tables: List<AggregateLogTable>): String {
        val branches = tables.withIndex().joinToString(" UNION ALL ") { (index, table) ->
            "(SELECT $index AS branch, sequence, ${AggregateLogTable.FACTS}, transport FROM ${table.log} " +
                "WHERE partition = ? AND sequence > ? ORDER BY sequence LIMIT ?)"
        }
        val entries = if (tables.isEmpty()) {
            "SELECT NULL::integer AS branch, NULL::bigint AS sequence WHERE false"
        } else {
            "SELECT * FROM ($branches) AS merged ORDER BY sequence LIMIT ?"
        }
        return "SELECT counter.sequence AS head, entry.* FROM (SELECT sequence FROM $partitions WHERE partition = ?) " +
            "AS counter LEFT JOIN LATERAL ($entries) AS entry ON true ORDER BY entry.sequence"
    }

    /**
     * The stream of a request id within a tenant across [tables], the earliest committed when more than one aggregate
     * type holds it. Parameters per table: tenant, request id.
     */
    fun byRequest(tables: List<AggregateLogTable>): String {
        val branches = tables.withIndex().joinToString(" UNION ALL ") { (index, table) ->
            "(SELECT $index AS branch, ${AggregateLogTable.FACTS} FROM ${table.log} " +
                "WHERE tenant_id = ? AND request_id = ? LIMIT 1)"
        }
        return "SELECT * FROM ($branches) AS found ORDER BY commit_time, branch LIMIT 1"
    }

    /** A partition's counter is the sequence of its last committed entry: the bump commits with the insert. */
    val head = "SELECT sequence FROM $partitions WHERE partition = ?"

    companion object {
        /** The version of this layout. */
        const val FORMAT = 1

        /** The registry of aggregate tables, shared by the logs of every context. */
        const val REGISTRY = "wow_log_aggregate"

        const val PREFIX = "wow_log"
        const val PARTITION = "partition"
    }
}

/**
 * The log table of one aggregate type. A stream's facts take one column each, so the log can be queried in place;
 * the scope is two nullable columns and the causation two more, both null or both set. The transport is its own
 * nullable `jsonb` column (decision D25): only the tail reads it. The table has no aggregate name column: the table is
 * the aggregate type. Its keys are the position `(partition, sequence)`, the version `(aggregate_id, tenant_id,
 * version)` and the request `(tenant_id, request_id)`, all within the type.
 */
internal class AggregateLogTable(val aggregate: String, val base: String, private val partitions: String) {
    val log = "\"$base\""
    val versionKey = PostgresIdentifiers.limited("${base}_version_key")
    val requestKey = PostgresIdentifiers.limited("${base}_request_key")

    fun ddl(): String = """
        CREATE TABLE IF NOT EXISTS $log (
            partition integer NOT NULL,
            sequence bigint NOT NULL,
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
            CONSTRAINT "$versionKey" UNIQUE (aggregate_id, tenant_id, version),
            CONSTRAINT "$requestKey" UNIQUE (tenant_id, request_id)
        )
    """.trimIndent()

    /**
     * The whole append as one autocommitted statement, so it costs one round trip and holds the partition row's lock
     * only until that statement commits:
     *
     * - `current` reads the aggregate's version from this table;
     * - `bump` takes the partition's next sequence from the context's counter (locking its row), but only when the
     *   aggregate is exactly one version behind, so a version gap neither bumps nor inserts;
     * - the insert into this table uses that sequence. A concurrent append of the same version passes `bump` too (its
     *   subquery reads the statement's snapshot) and then hits the unique version key, which rolls the whole statement
     *   back, the bump included: no sequence is ever skipped.
     *
     * Returns the sequence, or no row when the version did not fit.
     */
    val append = """
        WITH current AS (
            SELECT coalesce(max(version), 0) AS version FROM $log WHERE aggregate_id = ? AND tenant_id = ?
        ), bump AS (
            UPDATE $partitions SET sequence = sequence + 1
            WHERE partition = ? AND (SELECT version FROM current) = ?
            RETURNING sequence
        )
        INSERT INTO $log (partition, sequence, tenant_id, aggregate_id, version, command_id, request_id,
            command_type, commit_time, events, owner_id, space_id, correlation_id, causation_id, headers, transport)
        SELECT ?, bump.sequence, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?::jsonb FROM bump
        RETURNING sequence
    """.trimIndent()

    val currentVersion = "SELECT coalesce(max(version), 0) FROM $log WHERE aggregate_id = ? AND tenant_id = ?"

    val read = "SELECT $FACTS FROM $log WHERE aggregate_id = ? AND tenant_id = ? AND version BETWEEN ? AND ? " +
        "ORDER BY version"

    val entries = "SELECT partition, sequence, $FACTS, transport FROM $log WHERE aggregate_id = ? AND tenant_id = ? " +
        "AND version BETWEEN ? AND ? ORDER BY version"

    val position = "SELECT partition, sequence FROM $log WHERE aggregate_id = ? AND tenant_id = ? AND version = ?"

    val byRequest = "SELECT $FACTS FROM $log WHERE tenant_id = ? AND request_id = ?"

    companion object {
        /** The facts of a stream: every column but the position and the transport. */
        const val FACTS = "tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, " +
            "events, owner_id, space_id, correlation_id, causation_id, headers"
    }
}
