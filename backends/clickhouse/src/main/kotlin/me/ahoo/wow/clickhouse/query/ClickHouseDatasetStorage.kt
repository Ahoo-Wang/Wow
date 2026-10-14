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

package me.ahoo.wow.clickhouse.query

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.query.dataset.QueryDataset
import java.time.Duration

/**
 * Where a dataset's records live, read from its [storage][QueryDataset.storage] binding:
 *
 * ```json
 * "storage": { "table": "db.shop_order_state_flat", "deduplicate": "FINAL" }
 * ```
 *
 * `table` is `database.table`, or a table of the default database ([ClickHouseDatasetOptions.database]);
 * `deduplicate` is `NONE` (the default) or `FINAL`, which merges a replacing or collapsing engine's rows at read time.
 * The Sink's `_state_flat` view already reads `_state FINAL`, so a dataset over it needs no `deduplicate`.
 */
@ExperimentalWowApi
internal data class ClickHouseDatasetStorage(
    val database: String,
    val table: String,
    val deduplicate: Deduplicate = Deduplicate.NONE,
) {
    enum class Deduplicate { NONE, FINAL }

    companion object {
        const val TABLE: String = "table"
        const val DEDUPLICATE: String = "deduplicate"
        private val KNOWN = setOf(TABLE, DEDUPLICATE)

        /**
         * The binding of [dataset], a table of [defaultDatabase] when it names no database; a binding this backend
         * cannot serve fails with an [IllegalArgumentException].
         */
        @JvmStatic
        @Suppress("ThrowsCount") // Each check states one way the binding can be malformed.
        fun of(dataset: QueryDataset, defaultDatabase: String): ClickHouseDatasetStorage {
            fun fail(message: String) = IllegalArgumentException("Dataset [${dataset.name}] $message.")
            val binding = dataset.storage
            val unknown = binding.propertyNames().filterNot(KNOWN::contains)
            if (unknown.isNotEmpty()) throw fail("has unknown ClickHouse storage properties $unknown")
            val table = binding.get(TABLE)?.takeIf { it.isString }?.stringValue()?.takeIf(String::isNotBlank)
                ?: throw fail("must name its ClickHouse [$TABLE]")
            val parts = table.split('.')
            if (parts.size > 2 || parts.any(String::isBlank)) throw fail("names an invalid table [$table]")
            val deduplicate = binding.get(DEDUPLICATE)?.let { node ->
                Deduplicate.entries.firstOrNull { node.isString && it.name == node.stringValue() }
                    ?: throw fail("must set [$DEDUPLICATE] to one of ${Deduplicate.entries}")
            } ?: Deduplicate.NONE
            return ClickHouseDatasetStorage(
                database = if (parts.size == 2) parts[0] else defaultDatabase,
                table = parts.last(),
                deduplicate = deduplicate,
            )
        }
    }
}

/** How a [ClickHouseDatasetQueryBackendFactory] reaches and runs its datasets. */
@ExperimentalWowApi
data class ClickHouseDatasetOptions(
    /** The database of a dataset whose `table` names none: `wow.clickhouse.database`, as the Sink writes there. */
    val database: String = "default",
    /** How long a table's columns and engine are reused before `system.columns` is read again. */
    val metadataTtl: Duration = Duration.ofMinutes(1),
    /**
     * Server settings every statement runs under, such as `max_memory_usage` or `max_rows_to_read`: ClickHouse fails
     * a query over such a limit rather than truncating it. The execution time comes from each query's budget
     * (`AdmittedQuery.timeout`, decision D150), not from here.
     */
    val settings: Map<String, String> = emptyMap(),
) {
    init {
        require(database.isNotBlank()) { "database must not be blank." }
        require(!metadataTtl.isNegative) { "metadataTtl must not be negative." }
        require(settings.keys.none { it in ClickHouseExecutor.timeoutSettings(Duration.ofSeconds(1)).keys }) {
            "The execution time comes from each query's budget, not from settings."
        }
    }
}
