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

import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import java.util.concurrent.ConcurrentHashMap

/**
 * The tables and statements of one context's log (decision D162). Names derive from the context and the aggregate
 * types (rule of D161, [PostgresNames]) and are part of the format, whose version is [FORMAT] (decision D59):
 *
 * - `wow_log__<context>__partition`: one counter row per partition, the context's, with the partition's lineage
 *   (decision D240): `parent` (null) and `epoch` (0) for every partition the log is created with, reserved for the
 *   online splits of 10.x;
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
            sequence bigint NOT NULL,
            parent integer,
            epoch integer NOT NULL DEFAULT 0
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

    /**
     * The lineage columns of the counter table (decision D240), added within format 1 to a table created without them:
     * its rows read as partitions the log was created with.
     */
    val partitionAdditions = "ALTER TABLE $partitions ADD COLUMN IF NOT EXISTS parent integer, " +
        "ADD COLUMN IF NOT EXISTS epoch integer NOT NULL DEFAULT 0"

    /**
     * Creates the partition counters of an empty counter table, each with no parent at epoch 0; a table that has
     * counters keeps them.
     */
    fun initializePartitions(partitionCount: Int) =
        "INSERT INTO $partitions (partition, sequence, parent, epoch) " +
            "SELECT p, 0, NULL, 0 FROM generate_series(0, ${partitionCount - 1}) AS p " +
            "WHERE NOT EXISTS (SELECT 1 FROM $partitions) ON CONFLICT (partition) DO NOTHING"

    /**
     * A partition's counter row, its lineage columns included when it has them: partition. `*` rather than the
     * columns, so that a log only validating a schema created before them (ddl validate) reads its partitions as the
     * log was created with them.
     */
    val partitionRow = "SELECT * FROM $partitions WHERE partition = ?"

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

    /**
     * The group commit of streams of [partition] across [tables] as one autocommitted statement, the multi-row form of
     * [AggregateLogTable.append]; [branches] is the index in [tables] of each stream's table, in the group's order:
     *
     * - `input` holds the streams, each with its place in the group (`ord`) and its table (`branch`);
     * - `current` reads each aggregate's version from its table, `fits` keeps the streams that follow it: an aggregate's
     *   n-th stream of the group (`links`, by version) fits when it is n versions ahead, so its streams fit together
     *   or not at all, ranked in the group's order;
     * - `bump` takes as many sequences from the partition's counter as streams fit (locking its row until the statement
     *   commits), none when none fits;
     * - one insert per table writes its fitting streams at `bump - fits + rank`: the group's sequences are contiguous
     *   and in its order, whichever tables they land in.
     *
     * A concurrent append that took a version a fitting stream needed, or a request key a stream repeats, makes the
     * insert hit a unique key, which rolls the whole statement back, the bump included: no sequence is ever skipped.
     * **Lock order.** Groups of different partitions do not share a counter row, but may insert one request key
     * (`(tenant_id, request_id)` is unique per table, not per partition): the second waits for the first to end. Taken
     * in two orders, two groups would wait for each other (a deadlock). So every group takes them in one order: its
     * tables in the order of [tables], which the log sorts by name, and each table's rows by tenant and request id
     * (their sequences stay in the group's order). The other keys cannot be shared: a version key belongs to one
     * aggregate, so to one partition, whose appends queue on its counter row, and the position key holds the partition.
     *
     * Parameters per stream: [AggregateLogTable.STREAM_PARAMETERS] of it. Returns per stream, in the group's order:
     * `ord`, the aggregate's current `version`, and the `sequence` it was committed at, null when it did not fit.
     */
    fun appendGroup(partition: Int, tables: List<AggregateLogTable>, branches: List<Int>): String {
        val values = branches.withIndex().joinToString(", ") { (ord, branch) ->
            "($ord, $branch, ?::text, ?::text, ?::integer, ?::text, ?::text, ?::text, ?::bigint, ?::jsonb, ?::text, " +
                "?::text, ?::text, ?::text, ?::jsonb, ?::jsonb, ?::text, ?::text, ?::boolean, ?::jsonb)"
        }
        val current = tables.withIndex().joinToString(" UNION ALL ") { (branch, table) ->
            "SELECT input.ord, coalesce((SELECT max(log.version) FROM ${table.log} AS log " +
                "WHERE log.aggregate_id = input.aggregate_id AND log.tenant_id = input.tenant_id), 0) AS version " +
                "FROM input WHERE input.branch = $branch"
        }
        val inserts = tables.withIndex().joinToString("") { (branch, table) ->
            ", appended_$branch AS (INSERT INTO ${table.log} (partition, sequence, ${AggregateLogTable.COLUMNS}) " +
                "SELECT $partition, bump.sequence - fits.total + fits.rank, " +
                AggregateLogTable.COLUMNS.split(", ").joinToString(", ") { "fits.$it" } +
                " FROM fits, bump WHERE fits.branch = $branch ORDER BY fits.tenant_id, fits.request_id RETURNING sequence)"
        }
        return """
            WITH input (ord, branch, ${AggregateLogTable.COLUMNS}) AS (VALUES $values),
            current AS ($current),
            links AS (
                SELECT input.ord, row_number() OVER (
                    PARTITION BY input.branch, input.tenant_id, input.aggregate_id ORDER BY input.version
                ) AS link
                FROM input
            ),
            fits AS (
                SELECT input.*, row_number() OVER (ORDER BY input.ord) AS rank, count(*) OVER () AS total
                FROM input JOIN current ON current.ord = input.ord JOIN links ON links.ord = input.ord
                WHERE input.version - current.version = links.link
            ), bump AS (
                UPDATE $partitions SET sequence = sequence + (SELECT count(*) FROM fits)
                WHERE partition = $partition AND EXISTS (SELECT 1 FROM fits)
                RETURNING sequence
            )$inserts
            SELECT current.ord, current.version, bump.sequence - fits.total + fits.rank AS sequence
            FROM current LEFT JOIN fits ON fits.ord = current.ord LEFT JOIN bump ON true
            ORDER BY current.ord
        """.trimIndent()
    }

    /**
     * The group commit of streams of several partitions across [tables] as one autocommitted statement (roadmap §6
     * item 15, decision D275 on PostgreSQL): [appendGroup] with each stream's partition in [parts], in the group's
     * order beside [branches]. Each partition takes its own run of sequences, bumping its own counter by the streams
     * of it that fit, ranked in the group's order, so each partition's sequences stay contiguous and in order.
     *
     * **Lock order.** The statement locks the counter rows of its partitions first, in partition order (`locked`, a
     * `FOR UPDATE` above the sort), before it bumps them or inserts anything: two groups that share partitions take
     * them in one order, and every group takes its counters before the request keys, which it then takes in
     * [appendGroup]'s order. A group waiting on a counter holds no request key, so no cycle forms.
     *
     * The partitions are parameters, not literals, so the statement's text depends on the group's shape alone (its
     * size and its tables) and PostgreSQL plans it once per shape, as it does [appendGroup].
     *
     * Parameters per stream: its partition, then [AggregateLogTable.STREAM_PARAMETERS] of it
     * ([ACROSS_STREAM_PARAMETERS] in all). Returns per stream, in the group's order: `ord`, the aggregate's current
     * `version`, and the `sequence` it was committed at in its partition, null when it did not fit.
     */
    fun appendGroupAcross(tables: List<AggregateLogTable>, branches: List<Int>): String {
        val values = branches.withIndex().joinToString(", ") { (ord, branch) ->
            "($ord, $branch, ?::integer, ?::text, ?::text, ?::integer, ?::text, ?::text, ?::text, ?::bigint, " +
                "?::jsonb, ?::text, ?::text, ?::text, ?::text, ?::jsonb, ?::jsonb, ?::text, ?::text, ?::boolean, ?::jsonb)"
        }
        val current = tables.withIndex().joinToString(" UNION ALL ") { (branch, table) ->
            "SELECT input.ord, coalesce((SELECT max(log.version) FROM ${table.log} AS log " +
                "WHERE log.aggregate_id = input.aggregate_id AND log.tenant_id = input.tenant_id), 0) AS version " +
                "FROM input WHERE input.branch = $branch"
        }
        val inserts = tables.withIndex().joinToString("") { (branch, table) ->
            ", appended_$branch AS (INSERT INTO ${table.log} (partition, sequence, ${AggregateLogTable.COLUMNS}) " +
                "SELECT fits.part, bump.sequence - fits.total + fits.rank, " +
                AggregateLogTable.COLUMNS.split(", ").joinToString(", ") { "fits.$it" } +
                " FROM fits JOIN bump ON bump.partition = fits.part WHERE fits.branch = $branch " +
                "ORDER BY fits.tenant_id, fits.request_id RETURNING sequence)"
        }
        return """
            WITH input (ord, branch, part, ${AggregateLogTable.COLUMNS}) AS (VALUES $values),
            current AS ($current),
            links AS (
                SELECT input.ord, row_number() OVER (
                    PARTITION BY input.branch, input.tenant_id, input.aggregate_id ORDER BY input.version
                ) AS link
                FROM input
            ),
            fits AS (
                SELECT input.*, row_number() OVER (PARTITION BY input.part ORDER BY input.ord) AS rank,
                    count(*) OVER (PARTITION BY input.part) AS total
                FROM input JOIN current ON current.ord = input.ord JOIN links ON links.ord = input.ord
                WHERE input.version - current.version = links.link
            ), counts AS (
                SELECT part, count(*) AS taken FROM fits GROUP BY part
            ), locked AS (
                SELECT partition FROM $partitions WHERE partition IN (SELECT part FROM counts)
                ORDER BY partition FOR UPDATE
            ), bump AS (
                UPDATE $partitions SET sequence = $partitions.sequence + counts.taken
                FROM counts WHERE $partitions.partition = counts.part
                    AND $partitions.partition IN (SELECT partition FROM locked)
                RETURNING $partitions.partition, $partitions.sequence
            )$inserts
            SELECT current.ord, current.version, bump.sequence - fits.total + fits.rank AS sequence
            FROM current LEFT JOIN fits ON fits.ord = current.ord LEFT JOIN bump ON bump.partition = fits.part
            ORDER BY current.ord
        """.trimIndent()
    }

    companion object {
        /** The version of this layout. */
        const val FORMAT = 1

        /** The parameters of a stream in [appendGroupAcross]: its partition, then its own. */
        const val ACROSS_STREAM_PARAMETERS = AggregateLogTable.STREAM_PARAMETERS + 1

        /** The registry of aggregate tables, shared by the logs of every context. */
        const val REGISTRY = "wow_log_aggregate"

        const val PREFIX = "wow_log"

        /** The columns [partitionAdditions] adds. */
        val PARTITION_ADDED = listOf("parent", "epoch")
        const val PARTITION = "partition"
    }
}

/**
 * The log table of one aggregate type. A stream's facts take one column each, so the log can be queried in place;
 * the scope is two nullable columns and the causation two more, both null or both set. The operator (decision D163)
 * is three nullable columns, `operator_id`, `operator_tenant_id` and `operator_cross_scope`, all null for an anonymous
 * caller. The schedules are among the events (decision D198). The tags after the commit (decision D259) are a nullable
 * `jsonb` column, `{key: [values]}`: null for a stream appended before format 1 learned them (until `migrate-data`
 * backfills it), `{}` for an aggregate without tags. The operator and the tags are columns added within format 1, so a
 * table created before them gets them on its next ensure ([additions]). The transport is its own nullable `bytea`
 * column (decision D25): the bytes the runtime encoded, stored and given back unchanged (finding R5.B-3); only the
 * tail and the recovery read return it. The table has no aggregate name column: the table is
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
            transport bytea,
            operator_id text,
            operator_tenant_id text,
            operator_cross_scope boolean,
            tags jsonb,
            PRIMARY KEY (partition, sequence),
            CONSTRAINT "$versionKey" UNIQUE (aggregate_id, tenant_id, version),
            CONSTRAINT "$requestKey" UNIQUE (tenant_id, request_id)
        )
    """.trimIndent()

    /** Adds format 1's added columns (the operator, D163; the tags, D259) to a table created before them. */
    val additions = "ALTER TABLE $log ADD COLUMN IF NOT EXISTS operator_id text, " +
        "ADD COLUMN IF NOT EXISTS operator_tenant_id text, " +
        "ADD COLUMN IF NOT EXISTS operator_cross_scope boolean, " +
        "ADD COLUMN IF NOT EXISTS tags jsonb"

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
            command_type, commit_time, events, owner_id, space_id, correlation_id, causation_id, headers, transport,
            operator_id, operator_tenant_id, operator_cross_scope, tags)
        SELECT ?, bump.sequence, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::jsonb
        FROM bump
        RETURNING sequence
    """.trimIndent()

    val currentVersion = "SELECT coalesce(max(version), 0) FROM $log WHERE aggregate_id = ? AND tenant_id = ?"

    val read = "SELECT $FACTS FROM $log WHERE aggregate_id = ? AND tenant_id = ? AND version BETWEEN ? AND ? " +
        "ORDER BY version"

    /**
     * The tail of an aggregate from a version and the request keys of its latest streams before it, one statement
     * (decision D261): both branches scan the version key backwards from the aggregate's latest version; the second
     * leaves the stream's other facts out (`part` 1, NULL in their columns).
     */
    val tail = "SELECT 0 AS part, $FACTS FROM $log WHERE aggregate_id = ? AND tenant_id = ? AND version >= ? " +
        "UNION ALL (SELECT 1 AS part, tenant_id, aggregate_id, version, NULL, request_id, command_type, NULL, " +
        "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL FROM $log WHERE aggregate_id = ? AND tenant_id = ? " +
        "AND version < ? ORDER BY version DESC LIMIT ?) ORDER BY part, version"

    /**
     * An aggregate's snapshot in [snapshot] and its [tail] from the snapshot's version, in one statement and so one
     * snapshot of the database (decision D268): the snapshot row is `part` 2, its columns named `s_` plus those of
     * [PostgresSnapshotTables.READ_COLUMNS]; without a snapshot the tail is every stream and no earlier keys follow.
     * The parameters: the snapshot's tenant and id, the tail's id and tenant, the earlier keys' id, tenant and limit.
     */
    fun tailWithSnapshot(
        snapshot: String
    ): String = tailsWithSnapshot.computeIfAbsent(snapshot, ::buildTailWithSnapshot)

    /** [tailWithSnapshot] by snapshot table, built once each. */
    private val tailsWithSnapshot = ConcurrentHashMap<String, String>()

    private fun buildTailWithSnapshot(snapshot: String): String {
        val typedNulls = PostgresSnapshotTables.READ_COLUMNS.joinToString(
            ", "
        ) { (name, type) -> "NULL::$type AS s_$name" }
        val nulls = PostgresSnapshotTables.READ_COLUMNS.joinToString(", ") { "NULL" }
        val factNulls = FACTS.split(",").joinToString(", ") { "NULL" }
        val read = PostgresSnapshotTables.READ_COLUMNS.joinToString(", ") { (name, type) -> "s.$name::$type" }
        return "WITH s AS (SELECT * FROM $snapshot WHERE tenant_id = ? AND aggregate_id = ?) " +
            "SELECT 0 AS part, $FACTS, $typedNulls FROM $log WHERE aggregate_id = ? AND tenant_id = ? " +
            "AND version >= coalesce((SELECT version FROM s), 1) " +
            "UNION ALL (SELECT 1 AS part, tenant_id, aggregate_id, version, NULL, request_id, command_type, NULL, " +
            "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, $nulls FROM $log WHERE aggregate_id = ? " +
            "AND tenant_id = ? AND version < (SELECT version FROM s) ORDER BY version DESC LIMIT ?) " +
            "UNION ALL SELECT 2 AS part, $factNulls, $read FROM s ORDER BY part, version"
    }

    val entries = "SELECT partition, sequence, $FACTS, transport FROM $log WHERE aggregate_id = ? AND tenant_id = ? " +
        "AND version BETWEEN ? AND ? ORDER BY version"

    val position = "SELECT partition, sequence FROM $log WHERE aggregate_id = ? AND tenant_id = ? AND version = ?"

    val byRequest = "SELECT $FACTS FROM $log WHERE tenant_id = ? AND request_id = ?"

    /**
     * The aggregates with a stream that records no tags (decision D259), after an aggregate, in the version key's order
     * `(aggregate_id, tenant_id)`: the scan walks that index from where the last page ended.
     */
    val untagged = "SELECT DISTINCT aggregate_id, tenant_id FROM $log WHERE tags IS NULL " +
        "AND (aggregate_id, tenant_id) > (?, ?) ORDER BY aggregate_id, tenant_id LIMIT ?"

    /** Records one version's tags, only where the stream records none. */
    val recordTags = "UPDATE $log SET tags = ?::jsonb WHERE aggregate_id = ? AND tenant_id = ? AND version = ? " +
        "AND tags IS NULL"

    companion object {
        /** The columns [additions] adds. */
        val ADDED = listOf("operator_id", "operator_tenant_id", "operator_cross_scope", "tags")

        /** The columns a stream is written to besides its position, in the order of its parameters. */
        const val COLUMNS = "tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, " +
            "events, owner_id, space_id, correlation_id, causation_id, headers, transport, operator_id, " +
            "operator_tenant_id, operator_cross_scope, tags"

        /** The parameters of one stream in [PostgresEventLogTables.appendGroup]: one per column of [COLUMNS]. */
        const val STREAM_PARAMETERS = 18

        /** The facts of a stream: every column but the position and the transport. */
        const val FACTS = "tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, " +
            "events, owner_id, space_id, correlation_id, causation_id, headers, operator_id, operator_tenant_id, " +
            "operator_cross_scope, tags"
    }
}
