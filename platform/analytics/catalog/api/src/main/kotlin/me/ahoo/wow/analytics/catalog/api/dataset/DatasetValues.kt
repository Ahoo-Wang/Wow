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

package me.ahoo.wow.analytics.catalog.api.dataset

import me.ahoo.wow.api.query.annotation.SensitivityLevel

/**
 * What a wide table is made of (decisions D238 ④, D259 7.2b-2): catalog [sources], each under an alias, the [joins]
 * that bring every source but the [subject] in, the [columns] it shows, and the [filters] its rows pass.
 *
 * - The [subject] is the principal source: its tenant, owner and space columns are the dataset's scope, as
 *   `tenant_id`, `owner_id` and `space_id`.
 * - Every join keys on the tenant: one of its keys pairs the tenant columns of the two sides, unless the joined
 *   source is a platform dimension, declared and approved as such.
 * - A reference to a source's column is `<alias>.<column>`, or `<alias>.<column>.<path>` for a value inside a JSON
 *   column; a computed column and a filter are expressions over such references and the functions the catalog allows.
 * - [identity] and [eventTime] name columns of the dataset: the one that identifies a row, and when it last changed.
 */
data class DatasetDefinition(
    val sources: List<DatasetSource>,
    val subject: String,
    val columns: List<DatasetColumn>,
    val joins: List<DatasetJoin> = emptyList(),
    val filters: List<String> = emptyList(),
    val identity: String? = null,
    val eventTime: String? = null,
)

/** The catalog source [sourceId] (`<connection>:<database>:<table>`), referred to as [alias] in the definition. */
data class DatasetSource(val alias: String, val sourceId: String)

/** How a join keeps the rows of the left side that have no match on the right. */
enum class DatasetJoinKind {
    /** Only the rows that have a match on both sides. */
    INNER,

    /** Every row of the left side, with the right side's columns empty where it has no match. */
    LEFT,
}

/**
 * Brings the source [alias] in, matched by [on]: each key pairs a column of the sources before it ([DatasetJoinKey.left])
 * with one of [alias] ([DatasetJoinKey.right]).
 */
data class DatasetJoin(
    val alias: String,
    val on: List<DatasetJoinKey>,
    val kind: DatasetJoinKind = DatasetJoinKind.INNER
)

/** One key of a join: the [left] column (`<alias>.<column>` of a source joined before) equals the [right] one. */
data class DatasetJoinKey(val left: String, val right: String)

/**
 * A column of the dataset, [name]d as queries read it: the value [from] a source (`<alias>.<column>[.<path>]`), or
 * the [expression] computed from such values. Its protection follows its lineage (decision D241): the highest of
 * the values it reads, with their mask when it reads one; [sensitivity], when given, decides it instead, and only an
 * approver lets it be lower than the lineage says.
 */
data class DatasetColumn(
    val name: String,
    val from: String? = null,
    val expression: String? = null,
    val title: String? = null,
    val sensitivity: DatasetSensitivity? = null,
)

/**
 * The protection a column is given: at [level], or none when [level] is null, its mask keeping [keepPrefix] and
 * [keepSuffix] characters.
 */
data class DatasetSensitivity(
    val level: SensitivityLevel? = null,
    val keepPrefix: Int = 0,
    val keepSuffix: Int = 0,
)

/** Where a dataset is in its life. */
enum class DatasetStatus {
    /** Drafted or revised, never published. */
    DRAFT,
}
