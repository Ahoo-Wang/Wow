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

package me.ahoo.wow.query.dataset

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.query.schema.QueryMask

/**
 * Where a dataset's protection comes from ([QueryDataset.lineage]).
 *
 * A dataset file states it as `"lineage"`, by its wire name: `source` (the default) or `none`.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 * Wow's own serves a lineage it does not know as [SOURCE], never as one that masks less.
 */
@ExperimentalWowApi
enum class QueryDatasetLineage {
    /**
     * From its source. Its backend says what its table holds ([QueryDatasetSources]), and the dataset masks each
     * column its source protects at least as strictly as the source does, whether it declares the column `sensitive`
     * or not. A dataset declares a column stricter than its source, never looser. When its backend cannot tell
     * what its table holds, the dataset is served [unavailable][QueryDataset.unavailable].
     */
    SOURCE,

    /**
     * None: its author asserts that its table holds no value a state marks sensitive, as an operator's own view or a
     * table of no Wow service may. It masks only what it declares `sensitive`.
     */
    NONE,

    /**
     * Its declaration's: whoever built it already derived its protection from its source, as the analytics catalog
     * does from `wow_catalog`, where loosening a protection takes an approver. A dataset file cannot state it.
     */
    DECLARED,
}

/**
 * What a dataset backend knows of the tables its datasets read: implemented by its
 * [QueryBindings][me.ahoo.wow.query.backend.QueryBindings], the engine asks it for the source of each dataset whose
 * [lineage][QueryDataset.lineage] is [QueryDatasetLineage.SOURCE], when the dataset is served and each time one joins.
 * Bindings that do not implement it know no source: each such dataset is served unavailable.
 */
@WowSpi
fun interface QueryDatasetSources {
    /** The source of the table [dataset] reads; null when this backend cannot tell what the table holds. */
    fun sourceOf(dataset: QueryDataset): QueryDatasetSource?

    companion object {
        /** Knows no table's source. */
        @JvmField
        val NONE: QueryDatasetSources = QueryDatasetSources { null }
    }
}

/**
 * The source of a dataset's table: what it is ([description], for a message), and each of its [columns] that holds a
 * value a state marks sensitive. A column it does not name holds none.
 */
@ExperimentalWowApi
class QueryDatasetSource @InternalWowApi constructor(
    /** What the table holds, such as `the state of shop.order, in shop_order_state_flat`. */
    val description: String,
    /** The protected columns, by name. */
    val columns: Map<String, Column> = emptyMap(),
) {
    /**
     * A column that holds a value a state marks sensitive.
     *
     * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
     * Wow's own refuses to read a column it does not know, as it refuses an [Opaque] one.
     */
    sealed interface Column

    /**
     * A column holding the one value at [path] of its source (`state.phone`), which the source masks at [level] by
     * [mask] (a custom strategy is resolved by the engine's strategies, as for a snapshot read).
     */
    class Masked @InternalWowApi constructor(
        val path: String,
        val level: SensitivityLevel,
        val mask: QueryMask = QueryMask.FULL,
    ) : Column {
        override fun equals(other: Any?): Boolean =
            this === other || other is Masked && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Masked(path=$path, level=$level, mask=$mask)"

        private fun valueFields(): List<Any?> = listOf(path, level, mask)

        companion object {
            /** A [Masked] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(path: String, level: SensitivityLevel, mask: QueryMask = QueryMask.FULL): Masked =
                Masked(path, level, mask)
        }
    }

    /**
     * A column holding a whole document ([paths] are the protected values in it, such as `state.phone`): a dataset
     * field masks a column whole, never one path in it, so a dataset cannot read such a column.
     */
    class Opaque @InternalWowApi constructor(val paths: List<String>) : Column {
        init {
            require(paths.isNotEmpty()) { "An opaque column holds at least one protected path." }
        }

        override fun equals(other: Any?): Boolean = this === other || other is Opaque && paths == other.paths

        override fun hashCode(): Int = paths.hashCode()

        override fun toString(): String = "Opaque(paths=$paths)"

        companion object {
            /** An [Opaque] of the given paths. */
            @JvmStatic
            fun of(paths: List<String>): Opaque = Opaque(paths)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is QueryDatasetSource && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "QueryDatasetSource(description=$description, columns=$columns)"

    private fun valueFields(): List<Any?> = listOf(description, columns)

    companion object {
        /** A [QueryDatasetSource] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(description: String, columns: Map<String, Column> = emptyMap()): QueryDatasetSource =
            QueryDatasetSource(description, columns)
    }
}
