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

package me.ahoo.wow.analytics.catalog.domain.dataset

import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.dataset.ApproveDatasetPublication
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetArtifact
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDefinition
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDrafted
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetNeedsRevision
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetPublicationRefused
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetPublicationRequested
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetPublished
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRetired
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRevised
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRevisionCleared
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRolledBack
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetStatus
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetViewsDropped
import me.ahoo.wow.analytics.catalog.api.dataset.DraftDataset
import me.ahoo.wow.analytics.catalog.api.dataset.DropDatasetViews
import me.ahoo.wow.analytics.catalog.api.dataset.PublishDataset
import me.ahoo.wow.analytics.catalog.api.dataset.RecompileDataset
import me.ahoo.wow.analytics.catalog.api.dataset.RefuseDatasetPublication
import me.ahoo.wow.analytics.catalog.api.dataset.RetireDataset
import me.ahoo.wow.analytics.catalog.api.dataset.ReviseDataset
import me.ahoo.wow.analytics.catalog.api.dataset.RollbackDataset
import me.ahoo.wow.analytics.catalog.domain.source.SourceRejections
import me.ahoo.wow.analytics.catalog.domain.warehouse.KnownAggregates
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.reject
import me.ahoo.wow.api.schedule.ScheduleRule

/**
 * A wide table over the catalog's sources (-2), named as its queries read it,
 * `<context>.<name>`: an analyst drafts and revises its definition, which the catalog checks against its sources
 * as they are each time ([DatasetBinder]), and publishes it, each version compiled into its own view
 * ([DatasetCompiler]) which the publication creates in the warehouse; queries read the current version until the
 * dataset is switched back to another or retired, whose views are dropped once the retention ends.
 */
@AggregateRoot(lifecycle = [], ownership = Ownership.TENANT)
@Description("A wide table over the catalog's sources: its definition, its versions and where each is published")
class Dataset(private val state: DatasetState) {
    @OnCommand
    suspend fun onDraft(
        command: Command<DraftDataset>,
        sources: DatasetSources,
        known: KnownAggregates
    ): DatasetDrafted {
        val body = command.body
        listOf(body.context, body.name).firstOrNull { !NAME.matches(it) }?.let {
            invalid("$it is not a dataset name: a letter, then letters, digits, _ and -.")
        }
        val id = AnalyticsCatalogContext.datasetIdOf(body.context, body.name)
        if (command.ref.id != id) {
            invalid("The dataset ${body.name} of ${body.context} is $id, not ${command.ref.id}.")
        }
        if (known.knows(body.context, body.name)) {
            reject(
                DatasetRejections.NAME_TAKEN,
                "${body.name} is an aggregate of ${body.context}: a dataset takes a name no aggregate has.",
            )
        }
        rejectUnbound(body.definition, sources)
        return DatasetDrafted(body.context, body.name, body.definition, sourcesOf(body.definition), body.title)
    }

    @OnCommand
    suspend fun onRevise(command: ReviseDataset, sources: DatasetSources): DatasetRevised? {
        if (command.definition == state.definition && command.title == state.title) {
            return null
        }
        rejectUnbound(command.definition, sources)
        return DatasetRevised(command.definition, sourcesOf(command.definition), command.title)
    }

    // Decision: a publication or a request for its approval, two kinds of event.
    @Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
    @OnCommand(
        produces = [DatasetPublished::class, DatasetPublicationRequested::class],
        schedules = [DropDatasetViews::class]
    )
    suspend fun onPublish(
        command: PublishDataset,
        sources: DatasetSources,
        warehouse: DatasetWarehouse,
        policy: DatasetPolicy,
        scope: DecideScope,
    ): Decision {
        val definition = checkNotNull(state.definition)
        val artifact = when (val compiled = compiled(definition, sources, policy)) {
            is DatasetCompilation.Invalid ->
                return Decision.reject(DatasetRejections.DEFINITION_INVALID, compiled.problems.joinToString(" "))

            is DatasetCompilation.Compiled -> compiled.artifact
        }
        val published = state.published()
        if (state.status == DatasetStatus.PUBLISHED && published?.definition == definition &&
            same(published.artifact, artifact)
        ) {
            return Decision.NoOp
        }
        val reasons = artifact.approvals + listOfNotNull(EVERY_PUBLICATION.takeIf { policy.approvePublications })
        if (reasons.isNotEmpty()) {
            if (state.requested == definition) return Decision.NoOp
            return Decision.events(DatasetPublicationRequested(definition, reasons))
        }
        return Decision.events(published(artifact, definition, warehouse, scope))
    }

    @Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
    @OnCommand(schedules = [DropDatasetViews::class])
    suspend fun onApprovePublication(
        command: ApproveDatasetPublication,
        sources: DatasetSources,
        warehouse: DatasetWarehouse,
        policy: DatasetPolicy,
        scope: DecideScope,
    ): DatasetPublished {
        val requested = state.requested ?: scope.reject(
            DatasetRejections.NOT_REQUESTED,
            "No publication of ${state.context}.${state.name} awaits an approver.",
        )
        return when (val compiled = compiled(requested, sources, policy)) {
            is DatasetCompilation.Invalid ->
                scope.reject(DatasetRejections.DEFINITION_INVALID, compiled.problems.joinToString(" "))

            is DatasetCompilation.Compiled -> published(compiled.artifact, requested, warehouse, scope)
        }
    }

    @OnCommand
    fun onRefusePublication(command: RefuseDatasetPublication): DatasetPublicationRefused {
        if (state.requested == null) {
            reject(
                DatasetRejections.NOT_REQUESTED,
                "No publication of ${state.context}.${state.name} awaits an approver.",
            )
        }
        if (command.reason.isBlank()) invalid("A refusal says why.")
        return DatasetPublicationRefused(command.reason)
    }

    /**
     * Compiles the published definition again after a source changed (-2). The same compilation
     * changes nothing (or clears a revision it needed). Another one is published as the next version only when its
     * contract holds: the view gives the same columns, by name and type, as the published one, and it takes no new
     * approval, so only its masking or its rendering differs. Anything else (a definition that no longer compiles,
     * columns that would change, a new approval) marks the dataset as needing revision; the published version goes on
     * serving while its view still gives the columns it was published with, and is refused otherwise.
     */
    // Decision: a publication, a revision needed or one cleared, three kinds of event.
    @Suppress("UnusedParameter", "ReturnCount") // No body; each outcome returns its decision.
    @OnCommand(produces = [DatasetPublished::class, DatasetNeedsRevision::class, DatasetRevisionCleared::class])
    suspend fun onRecompile(
        command: RecompileDataset,
        sources: DatasetSources,
        warehouse: DatasetWarehouse,
        policy: DatasetPolicy,
    ): Decision {
        val published = state.published()
        if (published == null || state.status !in RECOMPILED) return Decision.NoOp
        val current = published.artifact
        val compiled = compiled(published.definition, sources, policy)
        val artifact = (compiled as? DatasetCompilation.Compiled)?.artifact
        if (artifact != null && same(current, artifact)) {
            return if (state.status == DatasetStatus.NEEDS_REVISION) {
                Decision.events(DatasetRevisionCleared(current.version))
            } else {
                Decision.NoOp
            }
        }
        if (artifact == null) {
            return needsRevision((compiled as DatasetCompilation.Invalid).problems, holds(current, warehouse))
        }
        val problems = incompatibility(current, artifact, warehouse)
        if (problems.isNotEmpty()) return needsRevision(problems, holds(current, warehouse))
        val columns = try {
            warehouse.create(artifact)
        } catch (failed: DatasetWarehouseException) {
            val problem = "The view ${artifact.database}.${artifact.view}: ${failed.message}"
            return needsRevision(listOf(problem), holds(current, warehouse))
        }
        return Decision.events(
            DatasetPublished(artifact.copy(columns = columns), published.definition, recompiled = true)
        )
    }

    /** Why [next] does not keep [current]'s contract: new approvals, or columns of other names or types. */
    private suspend fun incompatibility(
        current: DatasetArtifact,
        next: DatasetArtifact,
        warehouse: DatasetWarehouse,
    ): List<String> {
        val unapproved = next.approvals - current.approvals.toSet()
        if (unapproved.isNotEmpty()) {
            return unapproved.map { "$it It takes an approver, which the published version did not." }
        }
        val columns = warehouse.describe(next.select)
            ?: return listOf("The warehouse cannot describe the recompiled view of ${current.context}.${current.name}.")
        if (columns == current.columns) return emptyList()
        val was = current.columns.map { "${it.name} ${it.type}" }
        val now = columns.map { "${it.name} ${it.type}" }
        return listOf("Its columns would change: ${was - now.toSet()} would become ${now - was.toSet()}.")
    }

    /** Whether [current]'s view still gives the columns it was published with: it can go on serving. */
    private suspend fun holds(current: DatasetArtifact, warehouse: DatasetWarehouse): Boolean {
        val view = "${DatasetCompiler.quote(current.database)}.${DatasetCompiler.quote(current.view)}"
        return warehouse.describe("SELECT * FROM $view") == current.columns
    }

    private fun needsRevision(problems: List<String>, serving: Boolean): Decision {
        val unchanged = state.status == DatasetStatus.NEEDS_REVISION && state.problems == problems &&
            state.stillServing == serving
        return if (unchanged) Decision.NoOp else Decision.events(DatasetNeedsRevision(problems, serving))
    }

    /** [definition] as the next version, against the catalog's [sources] and the [policy]'s database. */
    private suspend fun compiled(definition: DatasetDefinition, sources: DatasetSources, policy: DatasetPolicy) =
        DatasetCompiler.compile(
            definition,
            statesOf(definition, sources),
            state.context,
            state.name,
            state.nextVersion(),
            policy.database,
            state.title,
            approvedOnly = policy.approveSources,
        )

    /** [artifact] published: its view created, the end of a retirement cancelled. */
    private suspend fun published(
        artifact: DatasetArtifact,
        definition: DatasetDefinition,
        warehouse: DatasetWarehouse,
        scope: DecideScope,
    ): DatasetPublished {
        val columns = try {
            warehouse.create(artifact)
        } catch (failed: DatasetWarehouseException) {
            failedView(artifact, failed)
        }
        scope.cancelSchedule(DROP_VIEWS)
        return DatasetPublished(artifact.copy(columns = columns), definition)
    }

    @OnCommand(schedules = [DropDatasetViews::class])
    suspend fun onRollback(
        command: RollbackDataset,
        warehouse: DatasetWarehouse,
        scope: DecideScope
    ): DatasetRolledBack? {
        val version = state.version(command.version)
            ?: invalid("Version ${command.version} of ${state.context}.${state.name} was never published.")
        if (state.status == DatasetStatus.PUBLISHED && state.current == command.version) {
            return null
        }
        try {
            warehouse.create(version.artifact)
        } catch (failed: DatasetWarehouseException) {
            failedView(version.artifact, failed)
        }
        scope.cancelSchedule(DROP_VIEWS)
        return DatasetRolledBack(command.version, state.current)
    }

    @Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
    @OnCommand(schedules = [DropDatasetViews::class])
    fun onRetire(
        command: RetireDataset,
        policy: DatasetPolicy,
        scope: DecideScope
    ): DatasetRetired? = when (state.status) {
        DatasetStatus.DRAFT -> scope.reject(
            DatasetRejections.NOT_PUBLISHED,
            "${state.context}.${state.name} was never published: there is nothing to retire.",
        )

        DatasetStatus.RETIRED -> null
        DatasetStatus.PUBLISHED, DatasetStatus.NEEDS_REVISION -> {
            scope.schedule(DROP_VIEWS, ScheduleRule.after(policy.retention), DropDatasetViews)
            DatasetRetired(state.current)
        }
    }

    @Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
    @OnCommand
    suspend fun onDrop(command: DropDatasetViews, warehouse: DatasetWarehouse): DatasetViewsDropped? {
        if (state.status != DatasetStatus.RETIRED || state.dropped) {
            return null
        }
        val artifacts = state.versions.map { it.artifact }
        artifacts.groupBy({ it.database }, { it.view }).forEach { (database, views) -> warehouse.drop(database, views) }
        return DatasetViewsDropped(artifacts.map { "${it.database}.${it.view}" })
    }

    private fun failedView(artifact: DatasetArtifact, failed: DatasetWarehouseException): Nothing =
        reject(DatasetRejections.VIEW_FAILED, "The view ${artifact.database}.${artifact.view}: ${failed.message}")

    /** Whether [next] compiles to what [serving] is, but for its version. */
    private fun same(serving: DatasetArtifact, next: DatasetArtifact): Boolean =
        next.copy(
            version = serving.version,
            view = serving.view,
            ddl = next.ddl.replace(next.view, serving.view),
            columns = serving.columns,
        ) == serving

    /** Rejects [definition] when it does not hold against the catalog's [sources] as they are now. */
    private suspend fun rejectUnbound(definition: DatasetDefinition, sources: DatasetSources) {
        val result = DatasetBinder.bind(definition, statesOf(definition, sources))
        if (result is DatasetBinder.Result.Invalid) {
            reject(DatasetRejections.DEFINITION_INVALID, result.problems.joinToString(" "))
        }
    }

    private suspend fun statesOf(definition: DatasetDefinition, sources: DatasetSources) =
        sourcesOf(definition).associateWith { sources.source(it) }

    private fun sourcesOf(definition: DatasetDefinition): List<String> = definition.sources.map { it.sourceId }.distinct()

    private fun invalid(message: String): Nothing = reject(SourceRejections.INVALID, message)

    private companion object {
        val NAME = Regex("[A-Za-z][A-Za-z0-9_-]*")

        /** The command the end of a retirement sends, and the key of its schedule. */
        const val DROP_VIEWS = "analytics-catalog.dataset.DropDatasetViews"

        /** Why a publication takes an approver when the catalog's switch says every one does. */
        const val EVERY_PUBLICATION = "Every publication takes an approver (wow.analytics.catalog.approval.publications)."

        /** The statuses a source's change recompiles. */
        val RECOMPILED = setOf(DatasetStatus.PUBLISHED, DatasetStatus.NEEDS_REVISION)
    }
}
