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

package me.ahoo.wow.analytics.catalog.api.source

import me.ahoo.wow.api.query.annotation.SensitivityLevel

/** Where a source's description comes from. */
enum class SourceKind {
    /** A table an analyst provides: drafted from the warehouse's columns, its sensitivity reviewed by people. */
    ANALYST,

    /** A table the Wow Sink keeps: described by its trusted `wow_catalog` row, which only an analyst may tighten. */
    WOW,
}

/** Where a source is in its review. */
enum class SourceStatus {
    /** An analyst's table being drafted or revised: not yet usable by a dataset. */
    DRAFT,

    /** An analyst's table whose scope and string columns are reviewed. */
    REVIEWED,

    /** A Wow table whose trusted `wow_catalog` row is adopted. */
    ADOPTED,
}

/** A column of a table, as `system.columns` reads it: its [name] and its warehouse [type]. */
data class SourceColumn(val name: String, val type: String)

/**
 * How one value of a source is protected: the value at [path] (a column, or a JSON path below one such as
 * `state.customer.phone`), of the event [event] at [revision] when it is an event's (empty and 0 otherwise), at
 * [level], or not sensitive when [level] is null, which is a decision too; its mask keeps [keepPrefix] and [keepSuffix]
 * characters, or is the custom [strategy] a Wow schema names.
 */
data class FieldSensitivity(
    val path: String,
    val level: SensitivityLevel? = null,
    val keepPrefix: Int = 0,
    val keepSuffix: Int = 0,
    val strategy: String? = null,
    val event: String = "",
    val revision: Int = 0,
)

/**
 * The key a [FieldSensitivity] is reviewed and approved by: its path, and for an event's value
 * `<path>@<event>#<revision>`.
 */
fun FieldSensitivity.keyOf(): String = if (event.isEmpty()) path else "$path@$event#$revision"

/** A value of a source at [path] that refers to an aggregate: [aggregate] of [context]. */
data class SourceReference(val path: String, val context: String, val aggregate: String)

/**
 * The trusted `wow_catalog` row a Wow source adopted: the aggregate the table is of ([service],
 * [context], [aggregate], [role] such as `state_flat`, [ownership]), the row's [fingerprint], the account that wrote it
 * ([writer]) and when ([writtenAt]), and each event's revision.
 */
data class WowEnrichment(
    val service: String,
    val context: String,
    val aggregate: String,
    val role: String,
    val ownership: String,
    val fingerprint: String,
    val writer: String,
    val writtenAt: String,
    val eventRevisions: Map<String, Int> = emptyMap(),
)
