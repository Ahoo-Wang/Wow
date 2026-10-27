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

import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.source.ApproveSensitivityChange
import me.ahoo.wow.analytics.catalog.api.source.ApproveSource
import me.ahoo.wow.analytics.catalog.api.source.DraftSource
import me.ahoo.wow.analytics.catalog.api.source.MarkSourceReviewed
import me.ahoo.wow.analytics.catalog.api.source.ReviewSourceSensitivity
import me.ahoo.wow.analytics.catalog.api.source.ReviseSource
import me.ahoo.wow.analytics.catalog.api.source.SensitivityChangeApproved
import me.ahoo.wow.analytics.catalog.api.source.SourceApproved
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.api.source.SourceDrafted
import me.ahoo.wow.analytics.catalog.api.source.SourceKind
import me.ahoo.wow.analytics.catalog.api.source.SourceReviewed
import me.ahoo.wow.analytics.catalog.api.source.SourceRevised
import me.ahoo.wow.analytics.catalog.api.source.SourceSensitivityReviewed
import me.ahoo.wow.analytics.catalog.api.source.SourceStatus
import me.ahoo.wow.analytics.catalog.api.source.SourceTableChanged
import me.ahoo.wow.analytics.catalog.api.source.SourceTableMissing
import me.ahoo.wow.analytics.catalog.api.source.SyncSource
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichment
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichmentAdopted
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichmentLost
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichmentRefused
import me.ahoo.wow.analytics.catalog.api.source.keyOf
import me.ahoo.wow.analytics.catalog.domain.warehouse.CatalogRow
import me.ahoo.wow.analytics.catalog.domain.warehouse.CatalogTrust
import me.ahoo.wow.analytics.catalog.domain.warehouse.KnownAggregates
import me.ahoo.wow.analytics.catalog.domain.warehouse.TrustedWriters
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseTable
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.reject

/**
 * One data source of the catalog: a table or view of the warehouse, its id
 * [AnalyticsCatalogContext.sourceIdOf] of (connection, database, table), so each physical table is one source.
 *
 * - **An analyst's table** ((b)): [DraftSource] reads its columns from the warehouse; [ReviseSource] declares
 *   its tenant column (required), owner and space columns, and the columns that refer to an aggregate the catalog
 *   knows; [ReviewSourceSensitivity] records each string column's protection; [MarkSourceReviewed] completes the
 *   review (draft → reviewed). Revising a reviewed source, or its table changing under it, takes it back to its draft.
 * - **A Wow table** (②③): [SyncSource] adopts the table's `wow_catalog` row that the trust rules accept
 *   ([CatalogTrust]): the scope, references and sensitivity the Sink wrote, never lower than the source gave before;
 *   a lowered or vanished protection is kept and pending until [ApproveSensitivityChange]. An analyst may only tighten
 *   it ([ReviewSourceSensitivity]); a table that has a trusted row is not an analyst's to draft, and an analyst's
 *   source of a table that gets one becomes a Wow source. Refusals, adoptions and losses are events: the audit.
 *
 * Every source is in the platform's tenant ([AnalyticsCatalogContext.TENANT_ID], the type's fixed tenant), a
 * resource of that tenant ([Ownership.TENANT]); who may send what is the service's authorizer's to say.
 */
@AggregateRoot(lifecycle = [], ownership = Ownership.TENANT)
@Description("A data source of the warehouse: a Wow table or an analyst's table, its scope and its sensitivity")
class Source(private val state: SourceState) {
    @OnCommand
    suspend fun onDraft(
        command: Command<DraftSource>,
        catalog: WarehouseCatalog,
        writers: TrustedWriters,
    ): SourceDrafted {
        val body = command.body
        val id = AnalyticsCatalogContext.sourceIdOf(catalog.connection, body.database, body.table)
        if (command.ref.id != id) {
            invalid("The source of ${body.database}.${body.table} is $id, not ${command.ref.id}.")
        }
        if (body.database !in catalog.databases) {
            notFound(body.database, body.table)
        }
        val table = catalog.table(body.database, body.table) ?: notFound(body.database, body.table)
        val verdict = CatalogTrust.evaluate(table, catalog.catalogRows(body.database, body.table), writers)
        if (verdict is CatalogTrust.Verdict.Trusted) {
            reject(
                SourceRejections.WOW_SOURCE,
                "${table.database}.${table.name} is described by its trusted wow_catalog row: it is a Wow source.",
            )
        }
        return SourceDrafted(
            catalog.connection,
            catalog.engine,
            table.database,
            table.name,
            table.uuid,
            table.tableEngine,
            table.columns,
        )
    }

    @OnCommand
    suspend fun onRevise(command: ReviseSource, known: KnownAggregates): SourceRevised? {
        analystOnly()
        val scoped = listOfNotNull(command.tenantColumn, command.ownerColumn, command.spaceColumn)
        if (command.platform && scoped.isNotEmpty()) {
            invalid("A platform dimension is every tenant's: it declares no tenant, owner or space column.")
        }
        if (!command.platform && command.tenantColumn == null) {
            invalid("A source declares its tenant column, or that it is a platform dimension.")
        }
        val names = state.columns.mapTo(HashSet()) { it.name }
        val declared = scoped + command.references.map { it.path }
        declared.firstOrNull { it !in names }?.let { invalid("$it is not a column of ${state.table}.") }
        command.references.groupBy { it.path }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            invalid("$it refers to one aggregate at most.")
        }
        command.references.firstOrNull { !known.knows(it.context, it.aggregate) }?.let {
            reject(
                SourceRejections.UNKNOWN_AGGREGATE,
                "The catalog knows no aggregate ${it.aggregate} of ${it.context}: no Wow source is of it.",
            )
        }
        val unchanged = command.tenantColumn == state.tenantColumn && command.ownerColumn == state.ownerColumn &&
            command.spaceColumn == state.spaceColumn && command.references == state.references &&
            command.platform == state.platform
        if (unchanged) {
            return null
        }
        return SourceRevised(
            command.tenantColumn,
            command.ownerColumn,
            command.spaceColumn,
            command.references,
            reopened = state.status == SourceStatus.REVIEWED,
            platform = command.platform,
        )
    }

    @OnCommand
    fun onReview(command: ReviewSourceSensitivity): SourceSensitivityReviewed? {
        if (!state.exists) {
            invalid("No source to review.")
        }
        val fields = command.fields
        if (fields.isEmpty()) {
            invalid("A review names the values it decides.")
        }
        fields.groupBy { it.keyOf() }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            invalid("$it is reviewed once per review.")
        }
        fields.firstOrNull { it.keepPrefix < 0 || it.keepSuffix < 0 }?.let {
            invalid("${it.keyOf()} keeps no negative number of characters.")
        }
        val problem = when (state.kind) {
            SourceKind.ANALYST -> fields.firstOrNull { field ->
                field.event.isNotEmpty() || state.columns.none { it.name == field.path }
            }?.let { "${it.keyOf()} is not a column of ${state.table}: an analyst's source is reviewed by column." }

            SourceKind.WOW -> fields.firstOrNull { field ->
                SourceSensitivity.rank(field.level) < SourceSensitivity.rank(state.sensitivityOf(field.keyOf())?.level)
            }?.let {
                reject(
                    SourceRejections.LOOSENING,
                    "${it.keyOf()} is ${state.sensitivityOf(
                        it.keyOf()
                    )?.level}: a Wow source's value is only tightened.",
                )
            }
        }
        problem?.let(::invalid)
        val changed = fields.filter { it != state.sensitivityOf(it.keyOf()) }
        if (changed.isEmpty()) {
            return null
        }
        return SourceSensitivityReviewed(changed, reopened = state.status == SourceStatus.REVIEWED)
    }

    @OnCommand
    fun onMarkReviewed(@Suppress("UNUSED_PARAMETER") command: MarkSourceReviewed): SourceReviewed? {
        analystOnly()
        if (state.missing) {
            reject(SourceRejections.TABLE_MISSING, "${state.database}.${state.table} is gone.")
        }
        if (state.status == SourceStatus.REVIEWED) {
            return null
        }
        incomplete(state.columns, state.tenantColumn, platform = state.platform)?.let {
            reject(SourceRejections.INCOMPLETE, it)
        }
        return SourceReviewed
    }

    // Decision: a sync adopts, refuses or loses the row and follows the table, several kinds of event at once.
    @OnCommand(
        produces = [
            WowEnrichmentAdopted::class,
            WowEnrichmentRefused::class,
            WowEnrichmentLost::class,
            SourceTableChanged::class,
            SourceTableMissing::class,
        ],
    )
    suspend fun onSync(command: Command<SyncSource>, catalog: WarehouseCatalog, writers: TrustedWriters): Decision {
        val body = command.body
        val id = AnalyticsCatalogContext.sourceIdOf(catalog.connection, body.database, body.table)
        if (command.ref.id != id) {
            invalid("The source of ${body.database}.${body.table} is $id, not ${command.ref.id}.")
        }
        val table = catalog.table(body.database, body.table)
        if (table == null) {
            return if (state.exists && !state.missing) Decision.events(SourceTableMissing) else Decision.NoOp
        }
        val verdict = CatalogTrust.evaluate(table, catalog.catalogRows(body.database, body.table), writers)
        if (verdict is CatalogTrust.Verdict.Trusted) {
            return adopted(catalog.connection, catalog.engine, table, verdict.row)
        }
        if (!state.exists) {
            return Decision.NoOp
        }
        val events = followed(table, verdict)
        return if (events.isEmpty()) Decision.NoOp else Decision.events(*events.toTypedArray())
    }

    @OnCommand
    fun onApprove(command: ApproveSensitivityChange): SensitivityChangeApproved? {
        val keys = command.keys.ifEmpty { state.pending }.toSet()
        keys.firstOrNull { it !in state.pending }?.let { invalid("$it is not pending review.") }
        if (keys.isEmpty()) {
            return null
        }
        return SensitivityChangeApproved(
            keys.toList(),
            SourceSensitivity.approve(state.sensitivity, state.claimed, keys)
        )
    }

    @Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
    @OnCommand
    fun onApproveSource(command: ApproveSource): SourceApproved? = when {
        state.status == SourceStatus.DRAFT -> reject(
            SourceRejections.INCOMPLETE,
            "${state.database}.${state.table} is a draft: it is reviewed before it is approved.",
        )

        state.approved -> null
        else -> SourceApproved
    }

    /** What a known source records of its [table] that no trusted row describes, as the rules gave [verdict]. */
    private fun followed(table: WarehouseTable, verdict: CatalogTrust.Verdict): List<Any> = buildList {
        if (table.uuid != state.tableUuid || table.columns != state.columns || state.missing) {
            val reopened = state.status == SourceStatus.REVIEWED &&
                incomplete(
                    table.columns,
                    state.tenantColumn,
                    state.ownerColumn,
                    state.spaceColumn,
                    platform = state.platform,
                ) != null
            add(SourceTableChanged(table.uuid, table.tableEngine, table.columns, reopened))
        }
        if (state.kind == SourceKind.WOW) {
            if (!state.enrichmentLost) {
                add(WowEnrichmentLost((state.pending + state.claimed.map { it.keyOf() }).distinct()))
            }
            val reasons = (verdict as? CatalogTrust.Verdict.Refused)?.reasons.orEmpty()
            if (reasons.isNotEmpty() && reasons != state.refusals) {
                add(WowEnrichmentRefused(reasons))
            }
        }
    }

    private fun adopted(connection: String, engine: String, table: WarehouseTable, row: CatalogRow): Decision {
        val unchanged = state.kind == SourceKind.WOW && !state.enrichmentLost && !state.missing &&
            state.enrichment?.fingerprint == row.fingerprint && state.enrichment?.writtenAt == row.writtenAt &&
            state.tableUuid == table.uuid && state.columns == table.columns
        if (unchanged) {
            return Decision.NoOp
        }
        val claimed = SourceSensitivity.claimed(row.sensitive, table.columns)
        val (sensitivity, pending) = SourceSensitivity.adopt(state.sensitivity, state.claimed, claimed, state.pending)
        return Decision.events(
            WowEnrichmentAdopted(
                connection = connection,
                engine = engine,
                database = table.database,
                table = table.name,
                tableUuid = table.uuid,
                tableEngine = table.tableEngine,
                columns = table.columns,
                enrichment = WowEnrichment(
                    service = row.service,
                    context = row.context,
                    aggregate = row.aggregate,
                    role = row.role,
                    ownership = row.ownership,
                    fingerprint = row.fingerprint,
                    writer = row.writer,
                    writtenAt = row.writtenAt,
                    eventRevisions = row.eventRevisions,
                ),
                tenantColumn = row.tenantColumn,
                ownerColumn = row.ownerColumn.ifEmpty { null },
                spaceColumn = row.spaceColumn.ifEmpty { null },
                references = row.references,
                sensitivity = sensitivity,
                claimed = claimed,
                pending = pending,
                created = !state.exists,
                fromAnalyst = state.exists && state.kind == SourceKind.ANALYST,
            ),
        )
    }

    /** Why the analyst's source of [columns] is not complete, or null when it is; a [platform] one has no tenant. */
    private fun incomplete(
        columns: List<SourceColumn>,
        tenantColumn: String?,
        vararg declared: String?,
        platform: Boolean,
    ): String? {
        val names = columns.mapTo(HashSet()) { it.name }
        if (!platform && (tenantColumn == null || tenantColumn !in names)) {
            return "The source declares no tenant column of its table."
        }
        declared.filterNotNull().firstOrNull { it !in names }?.let { return "The declared column $it is gone." }
        val unreviewed = columns.filter { isString(it.type) && state.sensitivityOf(it.name) == null }.map { it.name }
        return unreviewed.takeIf { it.isNotEmpty() }?.let { "The string columns $it are not reviewed." }
    }

    /** Rejects a change to a source that is not an analyst's. */
    private fun analystOnly() {
        when {
            !state.exists -> invalid("No source to change.")
            state.kind == SourceKind.WOW -> reject(
                SourceRejections.WOW_SOURCE,
                "${state.database}.${state.table} is a Wow source: its row describes it, which an analyst only tightens.",
            )
        }
    }

    private fun invalid(message: String): Nothing = reject(SourceRejections.INVALID, message)

    private fun notFound(database: String, table: String): Nothing =
        reject(SourceRejections.TABLE_NOT_FOUND, "The warehouse has no table $database.$table to draft.")

    internal companion object {
        private val STRING_TYPES = Regex("String|JSON|Object\\(|Dynamic|Variant")

        /**
         * Whether a column of the warehouse [type] holds text an analyst reviews ((b)): any type that holds a
         * string, a JSON document or a value of any type, wrapped or not (`Nullable(String)`, `Array(String)`,
         * `Map(String, …)`, `LowCardinality(String)`).
         */
        fun isString(type: String): Boolean = STRING_TYPES.containsMatchIn(type)
    }
}
