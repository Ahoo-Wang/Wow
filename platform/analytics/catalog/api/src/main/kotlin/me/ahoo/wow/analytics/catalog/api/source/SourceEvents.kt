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

import me.ahoo.wow.api.annotation.Summary

/**
 * An analyst drafted the source of [database]'s [table] on [connection], a warehouse of the [engine] type
 * (`clickhouse`, decision D251 ⑤), with the table's uuid, its [tableEngine] (the kind of table as the warehouse names
 * it) and its columns.
 */
@Summary("An analyst's source was drafted")
data class SourceDrafted(
    val connection: String,
    val engine: String,
    val database: String,
    val table: String,
    val tableUuid: String,
    val tableEngine: String,
    val columns: List<SourceColumn>,
)

/** The analyst's source's scope and references were declared; [reopened] when it was reviewed before. */
@Summary("An analyst's source was revised")
data class SourceRevised(
    val tenantColumn: String,
    val ownerColumn: String? = null,
    val spaceColumn: String? = null,
    val references: List<SourceReference> = emptyList(),
    val reopened: Boolean = false,
)

/** A reviewer decided the protection of [fields]; [reopened] when an analyst's reviewed source went back to its draft. */
@Summary("A source's sensitivity was reviewed")
data class SourceSensitivityReviewed(val fields: List<FieldSensitivity>, val reopened: Boolean = false)

/** The analyst's source is reviewed: a dataset may be built on it. */
@Summary("An analyst's source was reviewed")
data object SourceReviewed

/**
 * The source's table is another table now ([tableUuid]) or has other columns; [reopened] when an analyst's reviewed
 * source went back to its draft, because a declared column is gone or a string column is not reviewed.
 */
@Summary("A source's table changed")
data class SourceTableChanged(
    val tableUuid: String,
    val tableEngine: String,
    val columns: List<SourceColumn>,
    val reopened: Boolean = false,
)

/** The source's table is no longer in the warehouse. */
@Summary("A source's table is missing")
data object SourceTableMissing

/**
 * The catalog adopted the trusted `wow_catalog` row [enrichment] of the table (decision D241 ③): the table's uuid,
 * table engine and columns, the scope's columns, the [references], and the [sensitivity] the source now gives each value,
 * never lower than before; [pending] are the keys whose row now says lower or nothing, kept as they were until a
 * privileged reviewer approves the change. [created] when the table had no source before; [fromAnalyst] when an
 * analyst's source of the table became a Wow source.
 */
@Summary("A trusted wow_catalog row was adopted")
data class WowEnrichmentAdopted(
    val connection: String,
    val engine: String,
    val database: String,
    val table: String,
    val tableUuid: String,
    val tableEngine: String,
    val columns: List<SourceColumn>,
    val enrichment: WowEnrichment,
    val tenantColumn: String,
    val ownerColumn: String? = null,
    val spaceColumn: String? = null,
    val references: List<SourceReference> = emptyList(),
    val sensitivity: List<FieldSensitivity> = emptyList(),
    val claimed: List<FieldSensitivity> = emptyList(),
    val pending: List<String> = emptyList(),
    val created: Boolean = false,
    val fromAnalyst: Boolean = false,
)

/**
 * The table's `wow_catalog` rows were not adopted, for [reasons] (decision D241 ③): an untrusted writer, another
 * table's uuid, other columns than the table has. Recorded for the audit when the reasons change.
 */
@Summary("A table's wow_catalog rows were refused")
data class WowEnrichmentRefused(val reasons: List<String>)

/**
 * The Wow source's trusted `wow_catalog` row is gone: each value keeps its protection, and the keys it gave are
 * [pending] review.
 */
@Summary("A Wow source's trusted wow_catalog row is gone")
data class WowEnrichmentLost(val pending: List<String>)

/** A privileged reviewer approved the lower protection of [keys]: the source's [sensitivity] is now as given. */
@Summary("A lower sensitivity was approved")
data class SensitivityChangeApproved(val keys: List<String>, val sensitivity: List<FieldSensitivity>)
