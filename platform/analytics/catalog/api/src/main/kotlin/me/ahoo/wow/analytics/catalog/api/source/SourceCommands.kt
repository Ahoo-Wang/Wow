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

import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary

/**
 * Drafts the analyst's source of [database]'s [table] (decision D238 ③(b)): the catalog reads the table's columns
 * from the warehouse (`system.columns`); the source's id is [me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext.sourceIdOf]
 * of the warehouse's connection, [database] and [table]. A table that the Wow Sink describes by a trusted `wow_catalog`
 * row is not an analyst's to draft (decision D241 ③).
 */
@CreateAggregate
@Summary("Draft an analyst's source from a warehouse table")
data class DraftSource(val database: String, val table: String)

/**
 * Declares an analyst's source's scope and references: the [tenantColumn] every row's tenant is in, the optional
 * [ownerColumn] and [spaceColumn], and the columns that refer to an aggregate the catalog knows ([references]). A
 * table every tenant shares, such as currencies or regions, is instead a [platform] dimension, without a tenant
 * column (decision D259 7.2b-2): a dataset joins it without a tenant key once an approver marks it reviewed. A
 * reviewed source goes back to its draft.
 */
@Summary("Revise an analyst's source")
data class ReviseSource(
    val tenantColumn: String? = null,
    val ownerColumn: String? = null,
    val spaceColumn: String? = null,
    val references: List<SourceReference> = emptyList(),
    val platform: Boolean = false,
)

/**
 * Records, value by value, the reviewer's decision on [fields]: sensitive at a level, or not. An analyst's source is
 * reviewed by column; a Wow source's values may only be tightened above what its `wow_catalog` row says (decision
 * D241 ③). Changing an analyst's reviewed source takes it back to its draft.
 */
@Summary("Review the sensitivity of a source's values")
data class ReviewSourceSensitivity(val fields: List<FieldSensitivity>)

/** Marks an analyst's source reviewed: its tenant column is declared and each of its string columns is reviewed. */
@Summary("Mark an analyst's source reviewed")
data object MarkSourceReviewed

/**
 * Brings a source up to date with the warehouse (decision D241 ④): its table's uuid and columns, and for a table the
 * Wow Sink describes, the `wow_catalog` row the trust rules adopt. The catalog sends it when it sees the table or its
 * `wow_catalog` rows change; it creates the Wow source of a table it does not know yet, and changes nothing for a
 * table that is neither known nor trusted.
 */
@AllowCreate
@Summary("Bring a source up to date with the warehouse")
data class SyncSource(val database: String, val table: String)

/**
 * Accepts, as a privileged reviewer, the lower protection the Wow source's `wow_catalog` row now gives the values
 * pending review ([keys], each a [FieldSensitivity.keyOf]; every pending one when empty): lowered or removed since
 * the source adopted it, or gone with the row (decision D241 ③).
 */
@Summary("Approve a lower sensitivity of a Wow source")
data class ApproveSensitivityChange(val keys: List<String> = emptyList())

/**
 * Approves, as an approver, a reviewed or adopted source for the datasets, when the catalog's switch says a new source
 * takes an approver (`wow.analytics.catalog.approval.sources`, decision D238 ③b): until then no dataset reads it.
 */
@Summary("Approve a source for the datasets")
data object ApproveSource
