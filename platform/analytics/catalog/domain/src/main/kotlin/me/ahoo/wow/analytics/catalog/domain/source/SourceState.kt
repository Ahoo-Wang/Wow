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

package me.ahoo.wow.analytics.catalog.domain.source

import me.ahoo.wow.analytics.catalog.api.source.FieldSensitivity
import me.ahoo.wow.analytics.catalog.api.source.SensitivityChangeApproved
import me.ahoo.wow.analytics.catalog.api.source.SourceApproved
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.api.source.SourceDrafted
import me.ahoo.wow.analytics.catalog.api.source.SourceKind
import me.ahoo.wow.analytics.catalog.api.source.SourceReference
import me.ahoo.wow.analytics.catalog.api.source.SourceReviewed
import me.ahoo.wow.analytics.catalog.api.source.SourceRevised
import me.ahoo.wow.analytics.catalog.api.source.SourceSensitivityReviewed
import me.ahoo.wow.analytics.catalog.api.source.SourceStatus
import me.ahoo.wow.analytics.catalog.api.source.SourceTableChanged
import me.ahoo.wow.analytics.catalog.api.source.SourceTableMissing
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichment
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichmentAdopted
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichmentLost
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichmentRefused
import me.ahoo.wow.analytics.catalog.api.source.keyOf

/**
 * One data source of the catalog (decisions D238 ③, D241): a table or view of the warehouse, [connection],
 * [database] and [table], as the catalog last saw it ([tableUuid], [tableEngine], [columns]; [missing] once the warehouse
 * no longer has it), the [kind] of its description and its [status].
 *
 * - The scope: the [tenantColumn] every row's tenant is in, and the optional [ownerColumn] and [spaceColumn].
 * - [references]: the values that refer to an aggregate.
 * - [sensitivity]: how each reviewed or adopted value is protected, by [FieldSensitivity.keyOf]; a string column of an
 *   analyst's source without one is not reviewed yet.
 * - A Wow source's [enrichment] is the `wow_catalog` row it adopted, [claimed] what that row says of each value,
 *   [pending] the keys a row lowered or no longer names, kept as they were until approved; [enrichmentLost] once no
 *   trusted row describes the table, and [refusals] the reasons its rows were last refused.
 */
class SourceState {
    var connection: String = ""
        private set
    var database: String = ""
        private set
    var table: String = ""
        private set
    var tableUuid: String = ""
        private set

    /** The engine type of the warehouse [connection] is: `clickhouse` (decision D251 ⑤). */
    var engine: String = ""
        private set
    var tableEngine: String = ""
        private set
    var columns: List<SourceColumn> = emptyList()
        private set
    var kind: SourceKind = SourceKind.ANALYST
        private set
    var status: SourceStatus = SourceStatus.DRAFT
        private set
    var missing: Boolean = false
        private set
    var tenantColumn: String? = null
        private set
    var ownerColumn: String? = null
        private set
    var spaceColumn: String? = null
        private set

    /** Whether an approver approved the source for the datasets; a source back to its draft is not. */
    var approved: Boolean = false
        private set

    /** Whether the source is a platform dimension: every tenant shares its rows, and it has no tenant column. */
    var platform: Boolean = false
        private set
    var references: List<SourceReference> = emptyList()
        private set
    var sensitivity: List<FieldSensitivity> = emptyList()
        private set
    var enrichment: WowEnrichment? = null
        private set
    var claimed: List<FieldSensitivity> = emptyList()
        private set
    var pending: List<String> = emptyList()
        private set
    var enrichmentLost: Boolean = false
        private set
    var refusals: List<String> = emptyList()
        private set

    /** Whether the source exists: it was drafted or adopted. */
    val exists: Boolean get() = table.isNotEmpty()

    /** The protection of [key], or null when it has none. */
    fun sensitivityOf(key: String): FieldSensitivity? = sensitivity.firstOrNull { it.keyOf() == key }

    fun onSourcing(event: SourceDrafted) {
        connection = event.connection
        engine = event.engine
        database = event.database
        table = event.table
        tableUuid = event.tableUuid
        tableEngine = event.tableEngine
        columns = event.columns
        kind = SourceKind.ANALYST
        status = SourceStatus.DRAFT
    }

    fun onSourcing(event: SourceRevised) {
        tenantColumn = event.tenantColumn
        ownerColumn = event.ownerColumn
        spaceColumn = event.spaceColumn
        references = event.references
        platform = event.platform
        status = SourceStatus.DRAFT
        approved = false
    }

    fun onSourcing(event: SourceSensitivityReviewed) {
        val keys = event.fields.mapTo(HashSet()) { it.keyOf() }
        sensitivity = sensitivity.filter { it.keyOf() !in keys } + event.fields
        if (event.reopened) {
            status = SourceStatus.DRAFT
            approved = false
        }
    }

    fun onSourcing(@Suppress("UNUSED_PARAMETER") event: SourceReviewed) {
        status = SourceStatus.REVIEWED
    }

    fun onSourcing(event: SourceTableChanged) {
        tableUuid = event.tableUuid
        tableEngine = event.tableEngine
        columns = event.columns
        missing = false
        if (event.reopened) {
            status = SourceStatus.DRAFT
            approved = false
        }
    }

    fun onSourcing(@Suppress("UNUSED_PARAMETER") event: SourceTableMissing) {
        missing = true
    }

    fun onSourcing(event: WowEnrichmentAdopted) {
        connection = event.connection
        engine = event.engine
        database = event.database
        table = event.table
        tableUuid = event.tableUuid
        tableEngine = event.tableEngine
        columns = event.columns
        kind = SourceKind.WOW
        status = SourceStatus.ADOPTED
        missing = false
        enrichment = event.enrichment
        tenantColumn = event.tenantColumn
        ownerColumn = event.ownerColumn
        spaceColumn = event.spaceColumn
        platform = false
        references = event.references
        sensitivity = event.sensitivity
        claimed = event.claimed
        pending = event.pending
        enrichmentLost = false
        refusals = emptyList()
    }

    fun onSourcing(event: WowEnrichmentRefused) {
        refusals = event.reasons
    }

    fun onSourcing(event: WowEnrichmentLost) {
        enrichmentLost = true
        claimed = emptyList()
        pending = event.pending
    }

    fun onSourcing(event: SensitivityChangeApproved) {
        sensitivity = event.sensitivity
        pending = pending - event.keys.toSet()
    }

    fun onSourcing(@Suppress("UNUSED_PARAMETER") event: SourceApproved) {
        approved = true
    }
}
