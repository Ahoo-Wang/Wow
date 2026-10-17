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

package me.ahoo.wow.migrate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import me.ahoo.wow.spi.log.EventLog
import kotlin.time.Duration.Companion.seconds

/**
 * The 9.x executions this map imports as parked entries of V10 subscriptions (decision D229 G-1, roadmap 8b.4):
 * [mappings] resolved against the [manifest] and the imported [aggregates].
 *
 * The 9.x compensation service may hold the failures of several services. This run takes those of the 9.x contexts
 * it migrates ([covers]): the contexts of its aggregates and of its subscription mappings. The others are another
 * run's.
 */
class CompensationPlan(
    val mappings: List<SubscriptionMapping>,
    private val manifest: Manifest,
    private val aggregates: Map<NineXAggregate, AggregatePlan>,
) {
    /** The mapping of each 9.x processor; one mapped twice keeps its last mapping here (the check reports it). */
    val processors: Map<NineXProcessor, SubscriptionMapping> = mappings.associateBy { it.source() }

    /** The 9.x contexts whose executions this run imports. */
    val contexts: Set<String> = (aggregates.keys.map { it.context } + processors.keys.map { it.context }).toSortedSet()

    /** Whether this run imports the executions of [function]: it is of a 9.x context the run migrates. */
    fun covers(function: NineXFunction): Boolean = function.context in contexts

    /** The V10 subscription [function]'s executions are parked for; null when its processor is not mapped. */
    fun subscriptionOf(function: NineXFunction): String? = processors[function.owner]?.subscriptionOf(function.name)

    /** The V10 subscription named [name] and the context declaring it; null when the Manifest has none. */
    fun manifestOf(name: String): Pair<ContextManifest, SubscriptionManifest>? =
        manifest.contexts.firstNotNullOfOrNull { context ->
            context.subscriptions.firstOrNull { it.name == name }?.let { context to it }
        }

    /**
     * Why [subscription]'s executions on events of [aggregate] cannot be parked in V10; null when they can: the
     * subscription is in the Manifest, the aggregate's streams are imported by this map, and the subscription reads
     * them from its own context's log, where a parked entry is resumed by version. A subscription of another
     * context's published events parks by topic offset (decision D134), which a 9.x execution has none of.
     */
    fun problemOf(subscription: String, aggregate: NineXAggregate): String? {
        val (context, manifest) = manifestOf(subscription)
            ?: return "the V10 subscription $subscription is not in the Manifest"
        val plan = aggregates[aggregate] ?: return "this map does not import the 9.x aggregate $aggregate"
        val target = plan.target
        val source = manifest.source
        return when {
            manifest.isReceiptHandler || manifest.job != null ->
                "the V10 subscription $subscription does not read events"

            (source.context ?: context.name) != context.name || context.name != target.context ->
                "the V10 subscription $subscription of ${context.name} does not read the log of ${target.context}"

            source.aggregates.isNotEmpty() && target.name !in source.aggregates ->
                "the V10 subscription $subscription does not read $target"

            else -> null
        }
    }

    /** The V10 parked entry of the unresolved 9.x [execution], or why there is none. */
    fun parkedOf(execution: NineXExecutionFailed): ParkedTarget {
        val subscription = subscriptionOf(execution.function)
            ?: return ParkedTarget.Unresolved("the 9.x processor ${execution.function.owner} has no subscription mapping")
        problemOf(subscription, execution.aggregate)?.let { return ParkedTarget.Unresolved(it) }
        val target = aggregates.getValue(execution.aggregate).target
        val entry = ParkedAggregate.of(
            subscription = subscription,
            ref = target.ref(execution.aggregateId.tenantId, execution.aggregateId.id),
            fromVersion = execution.version,
            reason = reasonOf(execution),
            failedAt = execution.executeAt,
            retries = execution.retries,
            // 9.x starts the retry state at the failure; V10 counts from the failure until the first retry started.
            retryAt = execution.retryAt.takeIf { execution.retries > 0 },
            retrySpec = ParkedRetrySpec.of(
                execution.maxRetries,
                execution.minBackoff.seconds,
                execution.executionTimeout.seconds,
            ),
            recoverable = recoverableOf(execution.recoverable),
        )
        return ParkedTarget.Parked(entry)
    }

    companion object {
        /**
         * The reason of [execution]'s parked entry: `<error code>: <message>`, as V10 writes `<exception class>:
         * <message>`, so the ops console groups the entries by the part before the colon (decision D215). The stack
         * trace stays behind.
         */
        fun reasonOf(execution: NineXExecutionFailed): String =
            if (execution.errorMessage.isBlank()) execution.errorCode else "${execution.errorCode}: ${execution.errorMessage}"

        /** The V10 mark of the 9.x `RecoverableType` [name] (9.x `MarkRecoverable`); an unknown name is [Recoverable.UNKNOWN]. */
        fun recoverableOf(name: String): Recoverable = Recoverable.entries.firstOrNull { it.name == name } ?: Recoverable.UNKNOWN
    }
}

/** The V10 parked entry of a 9.x execution, or why it has none. */
sealed interface ParkedTarget {
    data class Parked(val entry: ParkedAggregate) : ParkedTarget

    /** Not imported, for [reason]. */
    data class Unresolved(val reason: String) : ParkedTarget
}

/**
 * The import of the 9.x executions since it started: those [imported] as parked entries, those [already] parked in
 * V10, and the [unresolved] ones not imported; [others] are of the 9.x contexts another run migrates.
 */
data class CompensationProgress(
    val imported: Long,
    val already: Long,
    val unresolved: Long,
    val others: Long,
    val done: Boolean,
) {
    fun render(): String =
        "9.x compensation${if (done) " done" else ""}: ${imported + already + unresolved} unresolved executions, " +
            "$imported imported, $already already parked, $unresolved not imported; $others of other services left"
}

/**
 * Imports the unresolved executions of the 9.x compensation service as parked entries of the V10 service's failure
 * ledger (decisions D14, D141, D229 G-1), after the event streams, so a failure 9.x still retries is not lost at the
 * cut-over: the V10 service retries it automatically (roadmap P.1) and the ops service lists it.
 *
 * - **Unresolved only.** A succeeded execution, or one whose 9.x aggregate was deleted (skipped), is history and is
 *   not read; `FAILED` and `PREPARED` ones are imported.
 * - **One entry per execution.** The 9.x processor function's mapped subscription ([CompensationPlan.parkedOf]) parks
 *   the failed event's V10 aggregate from its version, with the 9.x retries, retry spec, recoverable mark and error
 *   code and message; never the stack trace (decision D215). An execution whose processor is not mapped, whose
 *   aggregate's streams are not imported, or whose version the log does not hold is reported `UNRESOLVED` and not
 *   imported.
 * - **Idempotent.** An aggregate the subscription already parks from that version or an earlier one is left as it
 *   is: V10 parks the aggregate from its earliest failure (decision D45), and its resume handles the later ones.
 * - **Checkpointed.** Executions are walked in id order, a page of [ImportOptions.pageSize] at a time, and the
 *   checkpoint ([CompensationCheckpoint]) moves after each page; [ImportOptions.restart] walks every one again. A dry
 *   run reads and resolves every execution and writes nothing; it does not look in the log, which it left empty.
 */
@Suppress("LongParameterList")
class CompensationImporter(
    private val source: NineXCompensationSource,
    private val plan: MigrationPlan,
    private val logOf: (String) -> EventLog,
    private val ledger: () -> FailureLedger,
    private val checkpoints: CheckpointRepository,
    private val options: ImportOptions = ImportOptions(),
    private val progress: (CompensationProgress) -> Unit = {},
    private val report: (String) -> Unit = {},
) {
    /** Imports the unresolved executions not yet imported, and marks the import complete. */
    suspend fun importAll(): CompensationProgress {
        val pending = plan.aggregates.keys.filter { checkpoints.load().of(it)?.completed != true }
        if (pending.isNotEmpty()) {
            throw MigrationException(
                "The full import of $pending has not completed: import the streams before the 9.x compensation.",
            )
        }
        var checkpoint = checkpoints.load().compensation?.takeUnless { options.restart } ?: CompensationCheckpoint()
        while (!checkpoint.completed) {
            val page = withContext(Dispatchers.IO) { source.unresolved(checkpoint.after, options.pageSize) }
            checkpoint = if (page.isEmpty()) {
                checkpoint.copy(completed = true)
            } else {
                page.fold(checkpoint.copy(after = page.last().id)) { counted, execution ->
                    when (import(execution)) {
                        Outcome.IMPORTED -> counted.copy(imported = counted.imported + 1)
                        Outcome.ALREADY -> counted.copy(already = counted.already + 1)
                        Outcome.UNRESOLVED -> counted.copy(unresolved = counted.unresolved + 1)
                        Outcome.OTHER -> counted.copy(others = counted.others + 1)
                    }
                }
            }
            checkpoints.save(checkpoints.load().with(checkpoint))
            if (!checkpoint.completed) {
                progress(checkpoint.progress())
            }
        }
        return checkpoint.progress().also(progress)
    }

    private suspend fun import(execution: NineXExecutionFailed): Outcome {
        val compensation = plan.compensation
        if (!compensation.covers(execution.function)) {
            return Outcome.OTHER
        }
        val target = compensation.parkedOf(execution)
        if (target is ParkedTarget.Unresolved) {
            return unresolved(execution, target.reason)
        }
        val entry = (target as ParkedTarget.Parked).entry
        if (options.dryRun) {
            return Outcome.IMPORTED
        }
        if (logOf(entry.ref.type.context).positionOf(entry.ref, entry.fromVersion) == null) {
            return unresolved(execution, "the V10 log holds no version ${entry.fromVersion} of ${entry.ref}")
        }
        val ledger = ledger()
        val parked = ledger.parked(entry.subscription, entry.ref)
        if (parked != null && parked.fromVersion <= entry.fromVersion) {
            return Outcome.ALREADY
        }
        ledger.park(entry)
        return Outcome.IMPORTED
    }

    private fun unresolved(execution: NineXExecutionFailed, reason: String): Outcome {
        report("UNRESOLVED 9.x execution $execution: $reason; it is not imported")
        return Outcome.UNRESOLVED
    }

    private fun CompensationCheckpoint.progress() = CompensationProgress(imported, already, unresolved, others, completed)

    private enum class Outcome { IMPORTED, ALREADY, UNRESOLVED, OTHER }
}

/**
 * The compensation comparison of `compare`: every unresolved 9.x execution this run imports has a V10 parked entry of
 * its subscription and aggregate from its version or an earlier one. Counts the executions [compared], those that
 * [differ] and those [unresolved] (not imported, as the import reported them).
 */
data class CompensationComparisonResult(val compared: Long, val differ: Long, val unresolved: Long) {
    val passed: Boolean get() = differ == 0L

    fun render(): String =
        "9.x compensation compared: $compared unresolved executions, $differ differ, $unresolved not imported"
}

/** Compares the unresolved 9.x executions with the V10 parked entries, every execution on every run. */
class CompensationComparison(
    private val source: NineXCompensationSource,
    private val plan: MigrationPlan,
    private val ledger: () -> FailureLedger,
    private val pageSize: Int,
    private val report: (String) -> Unit = {},
) {
    suspend fun compare(): CompensationComparisonResult {
        var result = CompensationComparisonResult(0, 0, 0)
        var after: String? = null
        do {
            val page = withContext(Dispatchers.IO) { source.unresolved(after, pageSize) }
            page.filter { plan.compensation.covers(it.function) }.forEach { result = compare(it, result) }
            after = page.lastOrNull()?.id
        } while (page.isNotEmpty())
        return result.also { report(it.render()) }
    }

    private suspend fun compare(
        execution: NineXExecutionFailed,
        result: CompensationComparisonResult,
    ): CompensationComparisonResult {
        val target = plan.compensation.parkedOf(execution) as? ParkedTarget.Parked
            ?: return result.copy(unresolved = result.unresolved + 1)
        val entry = target.entry
        val parked = ledger().parked(entry.subscription, entry.ref)
        val v10 = when {
            parked == null -> "(none)"
            parked.fromVersion > entry.fromVersion -> "parked from v${parked.fromVersion}"
            else -> return result.copy(compared = result.compared + 1)
        }
        report("DIFFERS 9.x execution $execution: 9.x ${entry.subscription} v${entry.fromVersion}, V10 $v10")
        return result.copy(compared = result.compared + 1, differ = result.differ + 1)
    }
}
