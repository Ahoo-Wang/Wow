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

import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import java.time.Duration

/**
 * Where a dataset's records live, read from its [storage][QueryDataset.storage] binding:
 *
 * ```json
 * "storage": { "table": "bi_db.order_customer_wide", "deduplicate": "FINAL" }
 * ```
 *
 * `table` is `database.table`, or a table in the default database of its source; `deduplicate` is `NONE` (the
 * default) or `FINAL`, which merges a replacing or collapsing engine's rows at read time and makes reads
 * final-consistent; `source` names one of the backend's [ClickHouseSource]s, the default one when absent (dataset
 * platform design §5.1).
 */
data class ClickHouseDatasetStorage(
    val database: String,
    val table: String,
    val deduplicate: Deduplicate = Deduplicate.NONE,
    val source: String? = null,
) {
    enum class Deduplicate { NONE, FINAL }

    companion object {
        const val TABLE = "table"
        const val DEDUPLICATE = "deduplicate"
        const val SOURCE = "source"
        private val KNOWN = setOf(TABLE, DEDUPLICATE, SOURCE)

        // Each check states one way the binding can be malformed.
        @Suppress("ThrowsCount")
        /**
         * The binding of [dataset]: [defaultDatabase] is the default source's database, [sourceDatabases] each named
         * source's; a dataset naming a source not in it fails.
         */
        @JvmOverloads
        fun of(
            dataset: QueryDataset,
            defaultDatabase: String,
            sourceDatabases: Map<String, String> = emptyMap(),
        ): ClickHouseDatasetStorage {
            fun fail(message: String): QuerySchemaValidationException =
                QuerySchemaValidationException("Dataset [${dataset.namedAggregate}] $message.")
            val binding = dataset.storage
            val unknown = binding.properties().map { it.key }.filterNot(KNOWN::contains)
            if (unknown.isNotEmpty()) throw fail("has unknown ClickHouse storage properties $unknown")
            val table = binding.get(TABLE)?.takeIf { it.isString }?.stringValue()?.takeIf(String::isNotBlank)
                ?: throw fail("must name its ClickHouse [$TABLE]")
            val parts = table.split('.')
            if (parts.size > 2 || parts.any(String::isBlank)) throw fail("names an invalid table [$table]")
            val deduplicate = binding.get(DEDUPLICATE)?.let { node ->
                Deduplicate.entries.firstOrNull { node.isString && it.name == node.stringValue() }
                    ?: throw fail("must set [$DEDUPLICATE] to one of ${Deduplicate.entries}")
            } ?: Deduplicate.NONE
            val source = binding.get(SOURCE)?.let { node ->
                node.takeIf { it.isString }?.stringValue()?.takeIf { it in sourceDatabases }
                    ?: throw fail("names an unknown ClickHouse [$SOURCE] [$node]; the sources are ${sourceDatabases.keys}")
            }
            return ClickHouseDatasetStorage(
                database = if (parts.size == 2) parts[0] else source?.let(sourceDatabases::getValue) ?: defaultDatabase,
                table = parts.last(),
                deduplicate = deduplicate,
                source = source,
            )
        }
    }
}

/**
 * What one query may cost ClickHouse, sent as server settings so ClickHouse enforces it: a query over a limit fails
 * rather than returning a truncated result. `null` leaves the server's own setting.
 */
data class ClickHouseQueryLimits(
    val maxExecutionTime: Duration? = null,
    val maxRowsToRead: Long? = null,
    val maxResultRows: Long? = null,
    val maxMemoryUsage: Long? = null,
) {
    val settings: Map<String, String>
        get() = buildMap {
            maxExecutionTime?.let { put("max_execution_time", it.toSeconds().coerceAtLeast(1).toString()) }
            maxRowsToRead?.let { put("max_rows_to_read", it.toString()) }
            maxResultRows?.let { put("max_result_rows", it.toString()) }
            maxMemoryUsage?.let { put("max_memory_usage", it.toString()) }
            if (isNotEmpty()) {
                put("timeout_overflow_mode", "throw")
                put("read_overflow_mode", "throw")
                put("result_overflow_mode", "throw")
            }
        }

    companion object {
        @JvmField
        val NONE = ClickHouseQueryLimits()

        /** HTTP queries come from untrusted callers: a bounded default; in-process queries are trusted code. */
        @JvmField
        val DEFAULTS: Map<QueryEntry, ClickHouseQueryLimits> = mapOf(
            QueryEntry.HTTP to ClickHouseQueryLimits(
                maxExecutionTime = Duration.ofSeconds(30),
                maxMemoryUsage = 4L * 1024 * 1024 * 1024,
            ),
        )
    }
}

/** A named ClickHouse server a dataset can be read from: its [executor] and the [database] of a bare table name. */
data class ClickHouseSource(val executor: ClickHouseExecutor, val database: String)

/** How a [ClickHouseDatasetQueryBackendFactory] reaches its datasets. */
data class ClickHouseDatasetOptions(
    /** The database of a dataset whose `table` names no database. */
    val defaultDatabase: String = "default",
    /** The limits of each entry's queries; an entry without one runs under [ClickHouseQueryLimits.NONE]. */
    val limits: Map<QueryEntry, ClickHouseQueryLimits> = ClickHouseQueryLimits.DEFAULTS,
    /** How long a table's columns and engine are reused before `system.columns` is read again. */
    val metadataTtl: Duration = Duration.ofMinutes(1),
) {
    fun limits(entry: QueryEntry): ClickHouseQueryLimits = limits[entry] ?: ClickHouseQueryLimits.NONE
}
