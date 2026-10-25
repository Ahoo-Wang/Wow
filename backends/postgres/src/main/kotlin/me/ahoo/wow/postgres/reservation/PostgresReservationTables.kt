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

package me.ahoo.wow.postgres.reservation

import me.ahoo.wow.postgres.PostgresIdentifiers

/**
 * The reservations' table and statements. The column layout is part of the format, version [FORMAT] (decision D59):
 * format 2 keys a reservation by `(context, tenant_id, namespace, key)` (finding A1-3; the system tenant is the empty
 * string), names its owner by three columns (finding A1-10; the owner's context is the key's) and records the holds of
 * a pending reservation (finding A1-2). Format 1 (`(namespace, key)`, one `owner` text) had no format record, so a
 * table of it is refused: drop it.
 */
internal class PostgresReservationTables(val name: String) {
    init {
        require(IDENTIFIER.matches(name)) { "A reservation table name is a lower-case SQL identifier: $name." }
    }

    val table = "\"$name\""
    private val ownerIndex = PostgresIdentifiers.limited("${name}_owner")

    /**
     * A null `expires_at` marks a confirmed reservation, its `holds` empty and `version` the owner's fact that last
     * confirmed it; a pending one expires at that epoch millisecond, is held by the attempts in `holds` and has version
     * 0. The owner index lists an aggregate's keys.
     */
    val ddl = listOf(
        """
        CREATE TABLE IF NOT EXISTS $table (
            context text NOT NULL,
            tenant_id text NOT NULL,
            namespace text NOT NULL,
            key text NOT NULL,
            owner_aggregate text NOT NULL,
            owner_tenant_id text NOT NULL,
            owner_id text NOT NULL,
            expires_at bigint,
            holds text[] NOT NULL DEFAULT '{}',
            version integer NOT NULL DEFAULT 0,
            PRIMARY KEY (context, tenant_id, namespace, key)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS \"$ownerIndex\" ON $table (context, owner_aggregate, owner_tenant_id, owner_id)",
    )

    /**
     * Takes the key, or adds a hold to the owner's own live pending reservation, in one statement. The conflict update
     * locks the row and re-checks its condition against the latest committed row, so of concurrent reservations
     * exactly one takes a free key. The condition admits the key's owner or an expired pending reservation; a
     * confirmed one (null expiry) never passes `expires_at <= now`. The owner's confirmed reservation stays as it is;
     * its expired pending one starts afresh, its old holds dropped. Returns a row only when the owner now holds the
     * key.
     *
     * Parameters: the key's four columns, the owner's three, the expiry, the hold, then now three times.
     */
    val reserve = """
        INSERT INTO $table AS held ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ARRAY[?]::text[], 0)
        ON CONFLICT (context, tenant_id, namespace, key) DO UPDATE
        SET owner_aggregate = excluded.owner_aggregate, owner_tenant_id = excluded.owner_tenant_id,
            owner_id = excluded.owner_id,
            expires_at = CASE
                WHEN $SAME_OWNER AND held.expires_at IS NULL THEN NULL
                WHEN $SAME_OWNER AND held.expires_at > ? THEN greatest(held.expires_at, excluded.expires_at)
                ELSE excluded.expires_at
            END,
            holds = CASE
                WHEN $SAME_OWNER AND held.expires_at IS NULL THEN held.holds
                WHEN $SAME_OWNER AND held.expires_at > ?
                    THEN array_append(array_remove(held.holds, excluded.holds[1]), excluded.holds[1])
                ELSE excluded.holds
            END
        WHERE $SAME_OWNER OR held.expires_at <= ?
        RETURNING $OWNER_COLUMNS
    """.trimIndent()

    /** Removes a hold from the owner's pending reservation. Parameters: the hold, the key, the owner, the hold. */
    val abandon = "UPDATE $table SET holds = array_remove(holds, ?) WHERE $KEY AND $OWNER " +
        "AND expires_at IS NOT NULL AND ? = ANY(holds) RETURNING cardinality(holds)"

    /** Frees the owner's pending reservation without holds. Parameters: the key, the owner. */
    val abandoned = "DELETE FROM $table WHERE $KEY AND $OWNER AND expires_at IS NOT NULL AND cardinality(holds) = 0"

    /**
     * Confirms the owner's reservation, or takes the key confirmed when it is free or held by an expired pending one,
     * in one statement as [reserve] does, keeping the greater version (a pending row's is 0). Returns a row only when
     * the owner now holds the key confirmed.
     *
     * Parameters: the key's four columns, the owner's three, the version, then now.
     */
    val confirm = """
        INSERT INTO $table AS held ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, '{}', ?)
        ON CONFLICT (context, tenant_id, namespace, key) DO UPDATE
        SET owner_aggregate = excluded.owner_aggregate, owner_tenant_id = excluded.owner_tenant_id,
            owner_id = excluded.owner_id, expires_at = NULL, holds = '{}',
            version = greatest(held.version, excluded.version)
        WHERE $SAME_OWNER OR held.expires_at <= ?
        RETURNING expires_at
    """.trimIndent()

    /** Frees a pending reservation, or one confirmed at the version or below. Parameters: the key, owner, version. */
    val release = "DELETE FROM $table WHERE $KEY AND $OWNER AND (expires_at IS NOT NULL OR version <= ?)"

    /** Parameters: the key. */
    val held = "SELECT $OWNER_COLUMNS FROM $table WHERE $KEY"

    /** Parameters: the key, now. */
    val find = "SELECT $OWNER_COLUMNS FROM $table WHERE $KEY AND (expires_at IS NULL OR expires_at > ?)"

    companion object {
        /** The version of this layout (decision D59). */
        const val FORMAT = 2

        private val IDENTIFIER = Regex("[a-z_][a-z0-9_]{0,62}")
        private const val COLUMNS =
            "context, tenant_id, namespace, key, owner_aggregate, owner_tenant_id, owner_id, expires_at, holds, version"
        private const val OWNER_COLUMNS = "owner_aggregate, owner_tenant_id, owner_id, expires_at, holds, version"
        private const val KEY = "context = ? AND tenant_id = ? AND namespace = ? AND key = ?"
        private const val OWNER = "owner_aggregate = ? AND owner_tenant_id = ? AND owner_id = ?"
        private const val SAME_OWNER = "(held.owner_aggregate = excluded.owner_aggregate " +
            "AND held.owner_tenant_id = excluded.owner_tenant_id AND held.owner_id = excluded.owner_id)"
    }
}
