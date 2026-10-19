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
import me.ahoo.wow.api.aggregate.AggregateRef
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

    /**
     * Every subscription of the Manifest, where the import may have parked an execution: a mapping names its
     * subscriptions only by the functions of the executions it meets.
     */
    val subscriptions: List<String> =
        manifest.contexts.flatMap { context -> context.subscriptions.map { it.name } }.distinct()

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

    /**
     * Whether [entry], which the import parked for the 9.x [execution] (its [originOf]), still stands for it: the
     * execution is still unresolved in 9.x, of a context this run migrates, and still parks that aggregate for that
     * subscription from that version (finding R2.4-1). Null [execution]: 9.x no longer has it.
     */
    fun stillParks(entry: ParkedAggregate, execution: NineXExecutionFailed?): Boolean {
        if (execution == null || !execution.unresolved || !covers(execution.function)) {
            return false
        }
        val target = (parkedOf(execution) as? ParkedTarget.Parked)?.entry ?: return false
        return target.subscription == entry.subscription && target.ref == entry.ref &&
            target.fromVersion == entry.fromVersion
    }

    companion object {
        private const val ORIGIN_START = " [9.x execution "
        private const val ORIGIN_END = "]"

        /**
         * The reason of [execution]'s parked entry: `<error code>: <message> [9.x execution <id>]`, as V10 writes
         * `<exception class>: <message>`, so the ops console groups the entries by the part before the colon
         * (decision D215); the colon stays when the message is blank. The stack trace stays behind.
         *
         * The trailing mark names the execution the import parked the entry for ([originOf], finding R2.4-1): a later
         * import resolves the entry once 9.x no longer leaves the execution unresolved, and `compare` reports it until
         * then. An entry V10 parks itself, or re-parks after a failed resume, has a reason of its own and is never
         * touched.
         */
        fun reasonOf(execution: NineXExecutionFailed): String {
            val message = execution.errorMessage.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            return "${execution.errorCode}:$message$ORIGIN_START${execution.id}$ORIGIN_END"
        }

        /** The id of the 9.x execution the import parked an entry of [reason] for; null for any other entry. */
        fun originOf(reason: String): String? {
            val start = reason.lastIndexOf(ORIGIN_START)
            if (!reason.endsWith(ORIGIN_END) || start < 0) {
                return null
            }
            return reason.substring(start + ORIGIN_START.length, reason.length - ORIGIN_END.length)
                .takeIf { it.isNotBlank() && ' ' !in it }
        }

        /** The V10 mark of the 9.x `RecoverableType` [name] (9.x `MarkRecoverable`); an unknown name is [Recoverable.UNKNOWN]. */
        fun recoverableOf(name: String): Recoverable =
            Recoverable.entries.firstOrNull { it.name == name } ?: Recoverable.UNKNOWN
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
    val resolved: Long = 0,
) {
    fun render(): String =
        "9.x compensation${if (done) " done" else ""}: ${imported + already + unresolved} unresolved executions, " +
            "$imported imported, $already already parked, $unresolved not imported; $others of other services left" +
            if (resolved > 0) "; $resolved imported entries resolved, 9.x no longer leaving them unresolved" else ""
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
 * - **Convergent** (finding R2.4-1). 9.x keeps retrying while the migration runs, so an execution an early import
 *   parked may succeed, or be skipped, before the cut-over. Each entry the import parks names its execution in its
 *   reason ([CompensationPlan.reasonOf]); a walk that starts from the first execution (the first one, or the final
 *   `--restart`) first resolves every such entry whose execution 9.x no longer leaves unresolved
 *   ([CompensationPlan.stillParks]), then parks the executions still unresolved, so an aggregate that another,
 *   later execution still holds is parked again from that one's version. An entry without the mark, which V10 parked
 *   itself, is never touched.
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
        // A dry run of the streams records no checkpoint: it reads the executions whatever the streams' import stands at.
        val pending = plan.aggregates.keys.filter { checkpoints.load().of(it)?.completed != true }
        if (!options.dryRun && pending.isNotEmpty()) {
            throw MigrationException(
                "The full import of $pending has not completed: import the streams before the 9.x compensation.",
            )
        }
        var checkpoint = checkpoints.load().compensation?.takeUnless { options.restart } ?: CompensationCheckpoint()
        if (!options.dryRun && checkpoint.after == null && !checkpoint.completed) {
            // Saved first: a run stopped while it converges walks from the first execution again, and so parks again
            // what another execution still holds of an aggregate whose entry it resolved.
            checkpoints.update { it.with(checkpoint) }
            checkpoint = checkpoint.copy(resolved = converge())
        }
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
            checkpoints.update { it.with(checkpoint) }
            if (!checkpoint.completed) {
                progress(checkpoint.progress())
            }
        }
        return checkpoint.progress().also(progress)
    }

    /** Resolves each entry the import parked whose 9.x execution no longer stands for it; returns how many. */
    private suspend fun converge(): Long {
        val ledger = ledger()
        var resolved = 0L
        plan.compensation.importedEntries(ledger, source, options.pageSize) { entry, execution ->
            ledger.resolve(entry.subscription, entry.ref)
            report(
                "RESOLVED ${entry.subscription} ${entry.ref} from v${entry.fromVersion}: the 9.x execution " +
                    "${execution ?: CompensationPlan.originOf(entry.reason)} it was imported for is " +
                    statusOf(execution),
            )
            resolved++
        }
        return resolved
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
        return if (ledger.park(entry)) Outcome.IMPORTED else Outcome.ALREADY
    }

    private fun unresolved(execution: NineXExecutionFailed, reason: String): Outcome {
        report("UNRESOLVED 9.x execution $execution: $reason; it is not imported")
        return Outcome.UNRESOLVED
    }

    private fun CompensationCheckpoint.progress() =
        CompensationProgress(imported, already, unresolved, others, completed, resolved)

    private enum class Outcome { IMPORTED, ALREADY, UNRESOLVED, OTHER }
}

/**
 * The compensation comparison of `compare`, both ways: every unresolved 9.x execution this run imports has a V10
 * parked entry of its subscription and aggregate from its version or an earlier one, and every entry the import
 * parked still stands for an unresolved execution (finding R2.4-1). Counts the executions [compared], those that
 * [differ] and those [unresolved] (not imported, as the import reported them: no mapping or subscription that parks
 * them, or a version the log does not hold), and the imported entries that are [stale].
 */
data class CompensationComparisonResult(
    val compared: Long,
    val differ: Long,
    val unresolved: Long,
    val stale: Long = 0,
) {
    val passed: Boolean get() = differ == 0L && stale == 0L

    fun render(): String =
        "9.x compensation compared: $compared unresolved executions, $differ differ, $unresolved not imported; " +
            "$stale imported entries no longer unresolved in 9.x"
}

/** Compares the unresolved 9.x executions with the V10 parked entries, every execution on every run. */
class CompensationComparison(
    private val source: NineXCompensationSource,
    private val plan: MigrationPlan,
    private val logOf: (String) -> EventLog,
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
        var stale = 0L
        plan.compensation.importedEntries(ledger(), source, pageSize) { entry, execution ->
            report(
                "DIFFERS V10 ${entry.subscription} ${entry.ref} parked from v${entry.fromVersion}: the 9.x execution " +
                    "${execution ?: CompensationPlan.originOf(entry.reason)} it was imported for is " +
                    statusOf(execution),
            )
            stale++
        }
        return result.copy(stale = stale).also { report(it.render()) }
    }

    private suspend fun compare(
        execution: NineXExecutionFailed,
        result: CompensationComparisonResult,
    ): CompensationComparisonResult {
        val target = plan.compensation.parkedOf(execution) as? ParkedTarget.Parked
            ?: return result.copy(unresolved = result.unresolved + 1)
        val entry = target.entry
        if (logOf(entry.ref.type.context).positionOf(entry.ref, entry.fromVersion) == null) {
            return result.copy(unresolved = result.unresolved + 1)
        }
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

/**
 * Calls [stale] with each entry of [ledger] the import parked ([CompensationPlan.originOf]) that no longer stands for
 * its 9.x execution ([CompensationPlan.stillParks]), and that execution as [source] holds it now (null when gone).
 */
private suspend fun CompensationPlan.importedEntries(
    ledger: FailureLedger,
    source: NineXCompensationSource,
    pageSize: Int,
    stale: suspend (ParkedAggregate, NineXExecutionFailed?) -> Unit,
) {
    subscriptions.forEach { subscription ->
        var after: AggregateRef? = null
        do {
            val page = ledger.list(subscription, after, pageSize)
            page.forEach { entry ->
                CompensationPlan.originOf(entry.reason)?.let { origin ->
                    val execution = withContext(Dispatchers.IO) { source.execution(origin) }
                    if (!stillParks(entry, execution)) {
                        stale(entry, execution)
                    }
                }
            }
            after = page.lastOrNull()?.ref
        } while (page.size == pageSize)
    }
}

/** How the 9.x [execution] an imported entry was parked for no longer stands for it, for a report. */
private fun statusOf(execution: NineXExecutionFailed?): String = when {
    execution == null -> "gone from 9.x"
    execution.deleted -> "skipped in 9.x"
    execution.status == NineXExecutionFailed.SUCCEEDED -> "succeeded in 9.x"
    else -> "no longer parked for that aggregate and version by this map"
}
