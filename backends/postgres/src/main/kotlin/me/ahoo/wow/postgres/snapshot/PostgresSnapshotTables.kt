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

/**
 * The table and statements of one context's snapshots. The name derives from the context and is part of the format,
 * whose version is [FORMAT] (decision D59). The key leads with the tenant, so a query within one tenant and aggregate
 * scans one key range. The scope is two nullable columns, the owner and the space, which the query side filters on.
 *
 * The text columns the query side compares and sorts by use the `C` collation: they order by code point, as the query
 * backend orders the state's text values, and their indexes serve that order. Two partial indexes cover the predicates
 * admission adds to every query (decision D151): the tenant, the aggregate and the caller's owner or space, over the
 * snapshots that are not deleted.
 */
internal class PostgresSnapshotTables(context: String) {
    val base = "wow_snapshot_" + context.lowercase().replace(Regex("[^a-z0-9_]"), "_")
    val snapshot = "\"$base\""

    /** The partial index of the owner scope. */
    val ownerIndex: String = PostgresIdentifiers.limited(base + "_owner_ix")

    /** The partial index of the space scope. */
    val spaceIndex: String = PostgresIdentifiers.limited(base + "_space_ix")

    fun ddl(): List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $snapshot (
            tenant_id text COLLATE "C" NOT NULL,
            aggregate_name text COLLATE "C" NOT NULL,
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
            PRIMARY KEY (tenant_id, aggregate_name, aggregate_id)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS \"$ownerIndex\" ON $snapshot (tenant_id, aggregate_name, owner_id) WHERE NOT deleted",
        "CREATE INDEX IF NOT EXISTS \"$spaceIndex\" ON $snapshot (tenant_id, aggregate_name, space_id) WHERE NOT deleted",
    )

    /**
     * The whole version-guarded save as one autocommitted statement: insert, or on the key's conflict replace the
     * row only when the new version is higher. PostgreSQL locks the conflicting row and evaluates the guard against
     * its latest committed version, so concurrent saves leave the highest version whatever their order.
     *
     * Returns the version when the row was inserted or replaced, no row when the guard refused it.
     */
    val save = """
        INSERT INTO $snapshot AS stored (tenant_id, aggregate_name, aggregate_id, version, schema_fingerprint, state,
            owner_id, space_id, deleted, first_event_time, last_event_time, tags)
        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb)
        ON CONFLICT (tenant_id, aggregate_name, aggregate_id) DO UPDATE SET
            version = excluded.version,
            schema_fingerprint = excluded.schema_fingerprint,
            state = excluded.state,
            owner_id = excluded.owner_id,
            space_id = excluded.space_id,
            deleted = excluded.deleted,
            first_event_time = excluded.first_event_time,
            last_event_time = excluded.last_event_time,
            tags = excluded.tags
        WHERE excluded.version > stored.version
        RETURNING version
    """.trimIndent()

    val load = "SELECT * FROM $snapshot WHERE tenant_id = ? AND aggregate_name = ? AND aggregate_id = ?"

    companion object {
        /** The version of this layout. */
        const val FORMAT = 1
    }
}
