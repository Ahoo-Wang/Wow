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

package me.ahoo.wow.clickhouse.sink

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.clickhouse.sink.ClickHouseSql.identifier
import me.ahoo.wow.spi.Ddl
import java.security.MessageDigest

/**
 * A column as `system.columns` reads it: its [name] and its [type]; for a column ClickHouse computes on insert
 * (`MATERIALIZED`), [computed] is its comment, null for a stored one.
 */
@InternalWowApi
data class ClickHouseColumn(val name: String, val type: String, val computed: String? = null)

/**
 * A table or view of the Sink: its [name], the stored [columns] it has, or null when only its existence is checked,
 * and the columns it [computes][computed], which it has at least, each of its definition ([ComputedColumn.comment]).
 */
@InternalWowApi
data class ClickHouseObject(
    val name: String,
    val columns: List<ClickHouseColumn>?,
    val computed: List<ComputedColumn> = emptyList(),
) {
    /** Every column it has, as `system.columns` lists them: the stored ones, then the computed ones. */
    val all: List<ClickHouseColumn> get() = columns.orEmpty() + computed.map { ClickHouseColumn(it.name, it.type) }

    /**
     * Why the [actual] columns are not these, or null when they are: other stored columns (a stored one of a computed
     * one's name among them), or a computed column of
     * another definition (its type or expression changed, which ClickHouse cannot change without rewriting every
     * row). A computed column it does not expect, another writer's, is no reason; one it lacks is [missing].
     */
    fun mismatch(actual: List<ClickHouseColumn>): String? {
        val stored = if (computed.isEmpty()) actual else actual.filter { it.computed == null }
        if (stored != columns) {
            return "$name has the columns ${textOf(stored)}, not ${textOf(columns.orEmpty())}"
        }
        val changed = computed.mapNotNull { column ->
            val found = actual.firstOrNull { it.name == column.name }
            if (found == null || found.computed == column.comment) {
                null
            } else {
                "${column.name} ${found.type} (${found.computed}), which this version computes as " +
                    "${column.type} (${column.comment})"
            }
        }
        return changed.takeIf { it.isNotEmpty() }?.let {
            "$name has ${it.joinToString("; ")}: ClickHouse cannot change a column's definition without rewriting " +
                "every row"
        }
    }

    private fun textOf(columns: List<ClickHouseColumn>): String = columns.joinToString(", ", "[", "]") {
        "${it.name} ${it.type}"
    }

    /** The computed columns the [actual] columns lack, in order. */
    fun missing(actual: List<ClickHouseColumn>): List<ComputedColumn> =
        computed.filter { column -> actual.none { it.name == column.name } }
}

/**
 * The tables and views of one aggregate [type], in [database], named after the
 * type (`<ctx>_<agg>`, [prefixOf]):
 *
 * - `<ctx>_<agg>_state_events`, the base table the Sink inserts into: a ReplacingMergeTree partitioned by the month
 *   of `commit_time`, ordered by `(tenant_id, aggregate_id, version)`, one row per commit ([ClickHouseRows]); with a TTL
 *   on `commit_time` when [AggregateSinkOptions.historyTtl] is set.
 * - `<ctx>_<agg>_state`, a ReplacingMergeTree(version) ordered by `(tenant_id, aggregate_id)`, the current state:
 *   filled by the materialized view `<ctx>_<agg>_state_mv` from the base table, with the base table's columns but
 *   the events and the headers, which the history keeps ([STATE_COLUMNS]); and the columns ClickHouse computes from
 *   them when a row is inserted ([computed]): an access column per declared access attribute ([accessColumns]), then a
 *   flat column per top-level scalar of the state ([flatColumns]), each with its fingerprint in its comment.
 * - `<ctx>_<agg>_events`, a ReplacingMergeTree ordered by `(tenant_id, aggregate_id, version, sequence)`, one row
 *   per event: filled by `<ctx>_<agg>_events_mv`, which `ARRAY JOIN`s the
 *   base table's `events`; under the same TTL.
 * - `<ctx>_<agg>_state_flat`, a view of `_state FINAL`: the system columns and the flat columns `_state` computes
 *   ([FlatColumns]); its definition's [flatFingerprint] and those of the writers before it are kept
 *   in its comment ([FlatLineage]), so that during a rolling upgrade an older writer leaves a newer
 *   writer's view as it is.
 *
 * Without the history ([AggregateSinkOptions.history] false) only `_state` and `_state_flat` exist, and the Sink
 * inserts into `_state` directly ([target]).
 *
 * Every table and view carries the format of the Sink's tables in its comment ([FORMAT_COMMENT], `wow-sink-format:1`),
 * which the Sink checks before it writes: a table or view without it is not the Sink's ([hasFormat]).
 *
 * Every table remembers the last [deduplicationWindow] insert blocks (`non_replicated_deduplication_window`), so an
 * insert retried with the same token is dropped from the base table and, the inserts setting
 * `deduplicate_blocks_in_dependent_materialized_views`, from the views' tables too. Beyond that window (a replay
 * from an earlier checkpoint, an old owner beside the new one, a batch made up differently), the history tables'
 * `ReplacingMergeTree` engine, keyed by `ref@version` (and the event's sequence), merges a replayed row away:
 * a read with `FINAL` counts every commit and event once.
 */
@InternalWowApi
class ClickHouseSinkSchema(
    val type: AggregateType,
    val database: String,
    val aggregate: AggregateSinkOptions,
    val flatColumns: List<FlatColumn>,
    private val deduplicationWindow: Int = ClickHouseSinkOptions.DEFAULT_DEDUPLICATION_WINDOW,
    /** The access columns of `_state`, one per declared access attribute ([AccessColumns]). */
    val accessColumns: List<ComputedColumn> = emptyList(),
) {
    /** The columns `_state` computes, in its order: the access columns, then the flat ones. */
    val computed: List<ComputedColumn> = accessColumns + flatColumns.map(ComputedColumn::of)

    private val prefix = prefixOf(type)
    val stateEvents: String = "${prefix}_state_events"
    val state: String = stateOf(type)
    val events: String = "${prefix}_events"
    val stateFlat: String = "${prefix}_state_flat"
    val stateView: String = "${prefix}_state_mv"
    val eventsView: String = "${prefix}_events_mv"

    /** The table the Sink inserts into: the base table, or `_state` without the history. */
    val target: String get() = if (aggregate.history) stateEvents else state

    /** [name] in [database], quoted. */
    fun qualified(name: String): String = "${identifier(database)}.${identifier(name)}"

    /**
     * The statements that create what is missing, in order: the tables, then the materialized views that fill them.
     * The flat view is not among them: it is replaced only when its writers' lineage allows ([flatView]).
     */
    val ddl: List<String>
        get() = buildList {
            if (aggregate.history) {
                add(createTable(stateEvents, BASE_COLUMNS, mergeTree(BASE_ORDER)))
            }
            add(
                createTable(
                    state,
                    STATE_COLUMNS,
                    "ReplacingMergeTree(version)\nORDER BY (tenant_id, aggregate_id)\n" + settings(),
                    computed,
                )
            )
            if (aggregate.history) {
                add(createTable(events, EVENT_COLUMNS, mergeTree(EVENT_ORDER)))
                add(
                    materializedView(
                        stateView,
                        state,
                        "SELECT ${STATE_COLUMNS.joinToString(", ") { it.name }}\nFROM ${qualified(stateEvents)}",
                    ),
                )
                add(
                    materializedView(
                        eventsView,
                        events,
                        "SELECT ${EVENT_SELECT.joinToString(", ")}\nFROM ${qualified(stateEvents)}\n" +
                            "ARRAY JOIN events AS event, arrayEnumerate(events) AS sequence",
                    ),
                )
            }
        }

    /**
     * A materialized view [name] that fills [to] with [select]. The `SELECT` is in parentheses: after a plain `FROM
     * <table>` ClickHouse would read `COMMENT` as the table's alias.
     */
    private fun materializedView(name: String, to: String, select: String): String =
        "CREATE MATERIALIZED VIEW IF NOT EXISTS ${qualified(name)} TO ${qualified(to)} AS\n($select)\n" + comment()

    /**
     * The fingerprint of the flat view's definition: the first 16 hex digits of the SHA-256 of its
     * `SELECT`, its database aside, so that every writer of the same state schema has the same.
     */
    val flatFingerprint: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(flatDefinition(identifier(state)).toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it) }.take(FINGERPRINT_LENGTH)
    }

    /**
     * The statement that creates or replaces the flat view, its comment the format and the writers' [lineage], this
     * one's last: `wow-sink-format:1 wow-sink-flat:<fingerprint>,…`.
     */
    fun flatView(lineage: FlatLineage): String =
        "CREATE OR REPLACE VIEW ${qualified(stateFlat)} AS\n" + flatDefinition(qualified(state)) +
            "\nCOMMENT ${ClickHouseSql.literal("$FORMAT_COMMENT ${lineage.comment}")}"

    private fun flatDefinition(from: String): String = "SELECT ${flatSelect().joinToString(", ")}\nFROM $from FINAL"

    /**
     * The statements that add the computed columns [missing] to `_state`, each after the column before it in
     * [computed]'s order (the stored columns' last for the first), so that the table lists its columns as a table
     * created with them would. Metadata only: a part written before is computed when it is read, until it is merged.
     */
    fun addColumns(missing: List<ComputedColumn>): List<String> = missing.map { column ->
        val before = computed.getOrNull(computed.indexOf(column) - 1)?.name ?: STATE_COLUMNS.last().name
        "ALTER TABLE ${qualified(state)} ADD COLUMN IF NOT EXISTS ${column.definition} AFTER ${identifier(before)}"
    }

    /**
     * What [Ddl.VALIDATE] checks: every table and view with its columns, the materialized views by name; each of them
     * with the format in its comment ([hasFormat]).
     */
    val expected: List<ClickHouseObject>
        get() = buildList {
            if (aggregate.history) {
                add(ClickHouseObject(stateEvents, BASE_COLUMNS))
            }
            add(ClickHouseObject(state, STATE_COLUMNS, computed))
            if (aggregate.history) {
                add(ClickHouseObject(events, EVENT_COLUMNS))
                add(ClickHouseObject(stateView, null))
                add(ClickHouseObject(eventsView, null))
            }
            add(
                ClickHouseObject(
                    stateFlat,
                    FLAT_SYSTEM_COLUMNS + flatColumns.map { ClickHouseColumn(it.name, it.type) }
                )
            )
        }

    private fun createTable(
        name: String,
        columns: List<ClickHouseColumn>,
        engine: String,
        computed: List<ComputedColumn> = emptyList(),
    ): String = "CREATE TABLE IF NOT EXISTS ${qualified(name)}\n(\n" +
        (columns.map { "${identifier(it.name)} ${it.type}" } + computed.map { it.definition })
            .joinToString(",\n") { "    $it" } +
        "\n)\nENGINE = $engine\n" + comment()

    private fun comment(): String = "COMMENT ${ClickHouseSql.literal(FORMAT_COMMENT)}"

    /**
     * A history table: by the month of its commits, kept for the history's TTL; a row of the same key, a replayed
     * commit or event (the same facts), replaces the one before it.
     */
    private fun mergeTree(order: String): String = buildString {
        append("ReplacingMergeTree\nPARTITION BY toYYYYMM(commit_time)\nORDER BY ($order)\n")
        aggregate.historyTtl?.let {
            append("TTL toDateTime(commit_time) + toIntervalSecond(${it.inWholeSeconds})\n")
        }
        append(settings())
    }

    private fun settings(): String = "SETTINGS non_replicated_deduplication_window = $deduplicationWindow"

    private fun flatSelect(): List<String> = (FLAT_SYSTEM_COLUMNS.map { it.name } + flatColumns.map { it.name })
        .map(::identifier)

    override fun toString(): String = "ClickHouseSinkSchema($database.$prefix)"

    @InternalWowApi
    companion object {
        /** The format of the Sink's tables and views; raised only by an incompatible change of them. */
        const val FORMAT: Int = 1

        /** What the comment of each of the Sink's tables and views says: their [FORMAT]. */
        const val FORMAT_COMMENT: String = "wow-sink-format:$FORMAT"

        /** Whether a table's or view's [comment] says it is of the Sink's [FORMAT], among its words. */
        fun hasFormat(comment: String?): Boolean = comment?.split(' ')?.contains(FORMAT_COMMENT) == true

        /** The columns of the base table, one row per commit ([ClickHouseRows]). */
        val BASE_COLUMNS: List<ClickHouseColumn> = listOf(
            ClickHouseColumn("id", "String"),
            ClickHouseColumn("tenant_id", "String"),
            ClickHouseColumn("aggregate_id", "String"),
            ClickHouseColumn("version", "UInt32"),
            ClickHouseColumn("command_id", "String"),
            ClickHouseColumn("request_id", "String"),
            ClickHouseColumn("command_type", "LowCardinality(String)"),
            ClickHouseColumn("commit_time", "DateTime64(3, 'UTC')"),
            ClickHouseColumn("owner_id", "String"),
            ClickHouseColumn("space_id", "String"),
            ClickHouseColumn("deleted", "Bool"),
            ClickHouseColumn("access", "String"),
            ClickHouseColumn("correlation_id", "String"),
            ClickHouseColumn("causation_id", "String"),
            ClickHouseColumn("headers", "Map(String, String)"),
            ClickHouseColumn("operator_id", "String"),
            ClickHouseColumn("operator_tenant_id", "String"),
            ClickHouseColumn("operator_cross_scope", "Bool"),
            ClickHouseColumn("events", "Array(Tuple(name LowCardinality(String), revision UInt32, payload String))"),
            ClickHouseColumn("state", "String"),
            ClickHouseColumn("state_fingerprint", "String"),
            ClickHouseColumn("state_numbers", "Map(String, String)"),
        )

        /** The columns only the history keeps: `_state` has the others. */
        private val HISTORY_ONLY = setOf("headers", "events")

        /**
         * The columns of `_state`, the current state: the base table's, but the events and the headers, which only the
         * history needs. The Sink inserts these alone when it keeps no history.
         */
        val STATE_COLUMNS: List<ClickHouseColumn> = BASE_COLUMNS.filterNot { it.name in HISTORY_ONLY }

        /** The columns of `_events`, one row per event. */
        val EVENT_COLUMNS: List<ClickHouseColumn> = listOf(
            ClickHouseColumn("id", "String"),
            ClickHouseColumn("tenant_id", "String"),
            ClickHouseColumn("aggregate_id", "String"),
            ClickHouseColumn("version", "UInt32"),
            ClickHouseColumn("sequence", "UInt32"),
            ClickHouseColumn("event_name", "LowCardinality(String)"),
            ClickHouseColumn("event_revision", "UInt32"),
            ClickHouseColumn("payload", "String"),
            ClickHouseColumn("command_id", "String"),
            ClickHouseColumn("request_id", "String"),
            ClickHouseColumn("command_type", "LowCardinality(String)"),
            ClickHouseColumn("commit_time", "DateTime64(3, 'UTC')"),
            ClickHouseColumn("owner_id", "String"),
            ClickHouseColumn("space_id", "String"),
            ClickHouseColumn("correlation_id", "String"),
            ClickHouseColumn("causation_id", "String"),
        )

        /** The system columns `_state_flat` starts with, as `_state` has them. */
        val FLAT_SYSTEM_COLUMNS: List<ClickHouseColumn> =
            listOf("id", "tenant_id", "aggregate_id", "version", "commit_time", "owner_id", "space_id", "deleted", "access")
                .map { name -> STATE_COLUMNS.first { it.name == name } }

        private const val BASE_ORDER = "tenant_id, aggregate_id, version"
        private const val EVENT_ORDER = "tenant_id, aggregate_id, version, sequence"

        private val EVENT_SELECT: List<String> = EVENT_COLUMNS.map {
            when (it.name) {
                "sequence" -> "toUInt32(sequence) AS sequence"
                "event_name" -> "event.name AS event_name"
                "event_revision" -> "event.revision AS event_revision"
                "payload" -> "event.payload AS payload"
                else -> it.name
            }
        }

        /** The names of [type]'s tables start with `<ctx>_<agg>`, lower case, other characters than `[a-z0-9_]` as `_`. */
        fun prefixOf(type: AggregateType): String = "${type.context}_${type.name}".lowercase().replace(INVALID, "_")

        /**
         * The name of [type]'s `_state`, its current state: one row per aggregate after `FINAL`, its newest commit's,
         * with the access attributes after it, which a dataset bound to [type] reads its rows by.
         */
        fun stateOf(type: AggregateType): String = "${prefixOf(type)}_state"

        /** The names of every table and view of [type], whether it keeps its history or not. */
        fun namesOf(type: AggregateType): List<String> = prefixOf(type).let { prefix ->
            SUFFIXES.map { prefix + it }
        }

        /**
         * The tables and views that several of [types] would share: [prefixOf] maps distinct names to
         * one (`order-item` and `order_item`, `Order` and `order`, context `a_b` and aggregate `c` with context `a` and
         * aggregate `b_c`), and one aggregate's table may be another's (`order`'s `_state_events` is `order_state`'s
         * `_events`). By name, each with the aggregates that would share it; empty when there is none.
         */
        fun collisionsOf(types: Collection<AggregateType>): Map<String, List<AggregateType>> =
            types.distinct()
                .flatMap { type -> namesOf(type).map { it to type } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size > 1 }
                .toSortedMap()

        private val INVALID = Regex("[^a-z0-9_]")
        private val SUFFIXES = listOf("_state_events", "_state", "_events", "_state_flat", "_state_mv", "_events_mv")
        private const val FINGERPRINT_LENGTH = 16
    }
}

/**
 * The fingerprints of the flat view's definitions that writers created it with, oldest first, as its comment keeps
 * them (`wow-sink-flat:<fingerprint>,<fingerprint>`, after the format, [ClickHouseSinkSchema.flatView]): the last is
 * the view's definition.
 *
 * During a rolling upgrade, instances of two versions start and restart in turn, each creating the view of its own
 * state schema. A writer replaces the view only when its own fingerprint is not in the lineage yet, a newer
 * definition than every one before it; one whose fingerprint is there but not last was replaced by a newer writer and
 * leaves the view as it is. The lineage keeps the last [MAX] fingerprints; a view without one (created by an
 * operator) has an empty lineage, which the first writer replaces.
 */
@InternalWowApi
class FlatLineage(val fingerprints: List<String>) {
    /** The fingerprint of the view's definition; null when the lineage is empty. */
    val current: String? get() = fingerprints.lastOrNull()

    /** The comment that keeps this lineage. */
    val comment: String get() = PREFIX + fingerprints.joinToString(",")

    /** Whether a writer after the one of [fingerprint] replaced the view: that writer's definition is older. */
    fun supersedes(fingerprint: String): Boolean = fingerprint in fingerprints && current != fingerprint

    /** This lineage with [fingerprint] last, the oldest dropped beyond [MAX]. */
    fun then(fingerprint: String): FlatLineage = FlatLineage((fingerprints - fingerprint + fingerprint).takeLast(MAX))

    override fun equals(other: Any?): Boolean = this === other || other is FlatLineage && fingerprints == other.fingerprints

    override fun hashCode(): Int = fingerprints.hashCode()

    override fun toString(): String = "FlatLineage($fingerprints)"

    @InternalWowApi
    companion object {
        const val PREFIX: String = "wow-sink-flat:"
        const val MAX: Int = 16
        val EMPTY: FlatLineage = FlatLineage(emptyList())

        /** The lineage a view's [comment] keeps, from [PREFIX] on (after the format); empty when it keeps none. */
        fun of(comment: String?): FlatLineage {
            if (comment == null) {
                return EMPTY
            }
            val at = comment.indexOf(PREFIX)
            if (at < 0) {
                return EMPTY
            }
            val fingerprints = comment.substring(at + PREFIX.length).split(',')
            return FlatLineage(fingerprints.map { it.trim() }.filter { it.isNotEmpty() })
        }
    }
}
