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

package me.ahoo.wow.analytics.catalog.domain.warehouse

import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import java.security.MessageDigest

/**
 * Which warehouse accounts the catalog trusts to describe the tables of which contexts (decision D241 ③,
 * `wow.analytics.catalog.trusted-writers`): the account the Wow Sink of those contexts inserts as. The service binds
 * it from its configuration.
 */
fun interface TrustedWriters {
    /** Whether a `wow_catalog` row [writer] wrote is trusted to describe a table of [context]. */
    fun trusts(writer: String, context: String): Boolean

    companion object {
        /** Trusts nobody: no `wow_catalog` row is adopted. */
        @JvmField
        val NONE: TrustedWriters = TrustedWriters { _, _ -> false }

        /** Trusts each account of [writers] for the contexts it lists. */
        @JvmStatic
        fun of(writers: Map<String, Collection<String>>): TrustedWriters {
            val trusted = writers.mapValues { it.value.toSet() }
            return TrustedWriters { writer, context -> context in trusted[writer].orEmpty() }
        }
    }
}

/**
 * The trust rules of `wow_catalog` (decision D241 ③): a row is adopted only when every rule holds:
 *
 * 1. its `writer` is a [TrustedWriters] account, trusted for the row's context;
 * 2. its `table_uuid` is the table's current uuid, so a row of a table dropped and created again is not taken;
 * 3. its `columns_hash` is [columnsHash] of the table's current columns, so the table is the one the Sink describes.
 *
 * Of the rows that pass, the one written last is adopted: during a rolling upgrade several rows of one table stand side
 * by side, and the one that matches the physical columns wins (D241 ④). Without one, the reasons each row was refused.
 */
object CatalogTrust {
    /** What the rules made of a table's rows. */
    sealed interface Verdict {
        /** [row] is adopted. */
        data class Trusted(val row: CatalogRow) : Verdict

        /** No row passed, for [reasons]. */
        data class Refused(val reasons: List<String>) : Verdict

        /** The table has no row. */
        data object None : Verdict
    }

    fun evaluate(table: WarehouseTable, rows: List<CatalogRow>, writers: TrustedWriters): Verdict {
        val own = rows.filter { it.database == table.database && it.table == table.name }
        if (own.isEmpty()) {
            return Verdict.None
        }
        val hash = columnsHash(table.columns)
        val reasons = mutableListOf<String>()
        val trusted = own.filter { row ->
            val problem = when {
                !writers.trusts(row.writer, row.context) ->
                    "the writer ${row.writer} is not trusted for ${row.context}"
                row.tableUuid != table.uuid -> "the row is of the table ${row.tableUuid}, not ${table.uuid}"
                row.columnsHash != hash -> "the row expects other columns than the table has"
                else -> null
            }
            problem?.let { reasons += "${row.fingerprint.take(FINGERPRINT_PREFIX)}: $it" }
            problem == null
        }
        val adopted = trusted.maxByOrNull { it.writtenAt } ?: return Verdict.Refused(reasons.distinct().sorted())
        return Verdict.Trusted(adopted)
    }

    /**
     * The digest of [columns] a `wow_catalog` row's `columns_hash` is compared with: SHA-256, in lower-case hex, of
     * each column's `<name> <type>` as `system.columns` reads them, in order, one per line (as the Sink computes it).
     */
    fun columnsHash(columns: List<SourceColumn>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(columns.joinToString("\n") { "${it.name} ${it.type}" }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val FINGERPRINT_PREFIX = 12
}

/**
 * Answers whether the catalog knows an aggregate, which an analyst's column may refer to (decision D238 ③(b)): one a
 * Wow source of the catalog is of. The service binds it to the catalog's own snapshot query.
 */
fun interface KnownAggregates {
    suspend fun knows(context: String, aggregate: String): Boolean
}
