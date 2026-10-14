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

package me.ahoo.wow.postgres.snapshot

import me.ahoo.wow.postgres.PostgresIdentifiers

/**
 * The table and statements of one aggregate type's snapshots (decision D161): one table per bounded context and
 * aggregate, `wow_snapshot__<context>__<aggregate>`, so a query service can read one aggregate's table directly. Each
 * part is lower-cased with every character outside `[a-z0-9_]` as `_`, and the double underscore separates them; a
 * name longer than PostgreSQL's 63 characters is cut by [PostgresIdentifiers.limited]. The name is part of the format,
 * whose version is [FORMAT] and is recorded per table (decision D59). The key leads with the tenant, so a query within
 * one tenant scans one key range. The scope is two nullable columns, the owner and the space, which the query side
 * filters on. The operator and the first operator (decision D163) are nullable `jsonb` objects, `{id, tenantId,
 * crossScope}`, which the query side reads as the system fields `operator` and `firstOperator`; the schedules
 * (decision D198) a nullable `jsonb` object of each rule by its key, null when the aggregate keeps none. The state
 * revision (decision D172) is an integer, 1 for a row written before it; the stale version, null unless the row is
 * marked stale, the version up to which a rebuild must rewrite it. Columns added within format 1, so a table created
 * before them gets them on its next ensure ([additions]).
 *
 * The record of each table's claimed state revision (decision D172) is a row of [TABLES], shared by the snapshot
 * tables of the database, keyed by the table's name.
 *
 * The text columns the query side compares and sorts by use the `C` collation: they order by code point, as the query
 * backend orders the state's text values, and their indexes serve that order. Two partial indexes cover the predicates
 * admission adds to every query (decision D151): the tenant and the caller's owner or space, over the snapshots that
 * are not deleted.
 */
internal class PostgresSnapshotTables(context: String, aggregate: String) {
    val base: String = PostgresIdentifiers.of(PREFIX, context, aggregate)
    val snapshot = "\"$base\""

    /** The partial index of the owner scope. */
    val ownerIndex: String = PostgresIdentifiers.limited(base + "_owner_ix")

    /** The partial index of the space scope. */
    val spaceIndex: String = PostgresIdentifiers.limited(base + "_space_ix")

    fun ddl(): List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $snapshot (
            tenant_id text COLLATE "C" NOT NULL,
            aggregate_id text COLLATE "C" NOT NULL,
            version integer NOT NULL,
            schema_fingerprint text NOT NULL,
            state jsonb NOT NULL,
            owner_id text COLLATE "C",
            space_id text COLLATE "C",
            deleted boolean NOT NULL,
            first_event_time bigint NOT NULL,
            last_event_time bigint NOT NULL,
            tags jsonb NOT NULL,
            operator jsonb,
            first_operator jsonb,
            schedules jsonb,
            state_revision integer NOT NULL DEFAULT 1,
            stale_version integer,
            PRIMARY KEY (tenant_id, aggregate_id)
        )
        """.trimIndent(),
        additions,
        "CREATE INDEX IF NOT EXISTS \"$ownerIndex\" ON $snapshot (tenant_id, owner_id) WHERE NOT deleted",
        "CREATE INDEX IF NOT EXISTS \"$spaceIndex\" ON $snapshot (tenant_id, space_id) WHERE NOT deleted",
        """
        CREATE TABLE IF NOT EXISTS $TABLES (
            structure text PRIMARY KEY,
            revision integer NOT NULL,
            rebuilding boolean NOT NULL,
            backfilled boolean NOT NULL
        )
        """.trimIndent(),
    )

    /**
     * Adds format 1's added columns (the operators, D163; the schedules, D198; the state revision and the stale version,
     * D172) to a table created before them.
     */
    val additions: String
        get() = "ALTER TABLE $snapshot ADD COLUMN IF NOT EXISTS operator jsonb, " +
            "ADD COLUMN IF NOT EXISTS first_operator jsonb, ADD COLUMN IF NOT EXISTS schedules jsonb, " +
            "ADD COLUMN IF NOT EXISTS state_revision integer NOT NULL DEFAULT 1, " +
            "ADD COLUMN IF NOT EXISTS stale_version integer"

    private fun upsert(guard: String) = """
        INSERT INTO $snapshot AS stored (tenant_id, aggregate_id, version, schema_fingerprint, state,
            owner_id, space_id, deleted, first_event_time, last_event_time, tags, operator, first_operator, schedules,
            state_revision)
        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?)
        ON CONFLICT (tenant_id, aggregate_id) DO UPDATE SET
            version = excluded.version,
            schema_fingerprint = excluded.schema_fingerprint,
            state = excluded.state,
            owner_id = excluded.owner_id,
            space_id = excluded.space_id,
            deleted = excluded.deleted,
            first_event_time = excluded.first_event_time,
            last_event_time = excluded.last_event_time,
            tags = excluded.tags,
            operator = excluded.operator,
            first_operator = excluded.first_operator,
            schedules = excluded.schedules,
            state_revision = excluded.state_revision,
            stale_version = CASE WHEN stored.stale_version > excluded.version THEN stored.stale_version END
        WHERE $guard
        RETURNING version
    """.trimIndent()

    /**
     * The whole version-guarded save as one autocommitted statement: insert, or on the key's conflict replace the
     * row only when the new version is higher and its state revision is not lower (decision D172). PostgreSQL locks
     * the conflicting row and evaluates the guard against its latest committed version, so concurrent saves leave the
     * highest version whatever their order.
     *
     * Returns the version when the row was inserted or replaced, no row when the guard refused it.
     */
    val save = upsert("excluded.version > stored.version AND excluded.state_revision >= stored.state_revision")

    /**
     * After a refused save: marks the row stale up to the refused version when it is of a higher state revision and a
     * lower version (decision D172).
     */
    val markRefused = """
        UPDATE $snapshot SET stale_version = GREATEST(COALESCE(stale_version, 0), ?)
        WHERE tenant_id = ? AND aggregate_id = ? AND version < ? AND state_revision > ?
    """.trimIndent()

    /** The in-place write of a rebuild (decision D172): over the same or a lower version, whatever the revision. */
    val rewrite = upsert("excluded.version >= stored.version")

    /** Marks a row stale up to its own version. */
    val markStale = """
        UPDATE $snapshot SET stale_version = GREATEST(COALESCE(stale_version, 0), version)
        WHERE tenant_id = ? AND aggregate_id = ?
    """.trimIndent()

    private val pendingRows = "SELECT tenant_id, aggregate_id FROM $snapshot " +
        "WHERE (state_revision <> ? OR stale_version IS NOT NULL)"

    /** The first page of the rows a rebuild at a revision has to rewrite, in key order. */
    val pendingFirst = "$pendingRows ORDER BY tenant_id, aggregate_id LIMIT ?"

    /** A next page of [pendingFirst], after a key. */
    val pendingAfter = "$pendingRows AND (tenant_id, aggregate_id) > (?, ?) ORDER BY tenant_id, aggregate_id LIMIT ?"

    val load = "SELECT * FROM $snapshot WHERE tenant_id = ? AND aggregate_id = ?"

    /** The table's record. */
    val table = "SELECT revision, rebuilding, backfilled FROM $TABLES WHERE structure = ?"

    /**
     * A claim (decision D172): records the table, or raises its revision, or lowers it when the claim says so (the
     * fourth parameter); a change starts a rebuild. Returns the record when it was written, no row otherwise.
     */
    val claim = """
        INSERT INTO $TABLES AS recorded (structure, revision, rebuilding, backfilled) VALUES (?, ?, ?, false)
        ON CONFLICT (structure) DO UPDATE SET revision = excluded.revision, rebuilding = true
        WHERE excluded.revision > recorded.revision OR (?::boolean AND excluded.revision < recorded.revision)
        RETURNING revision, rebuilding, backfilled
    """.trimIndent()

    /** Sets the flags given (null keeps one) while the table is claimed at a revision. */
    val mark = """
        UPDATE $TABLES SET rebuilding = COALESCE(?::boolean, rebuilding), backfilled = COALESCE(?::boolean, backfilled)
        WHERE structure = ? AND revision = ?
        RETURNING revision, rebuilding, backfilled
    """.trimIndent()

    companion object {
        /** The version of this layout. */
        const val FORMAT = 1

        const val PREFIX = "wow_snapshot"

        /** The records of the snapshot tables' claimed state revisions (decision D172). */
        const val TABLES = "wow_snapshot_table"
    }
}
