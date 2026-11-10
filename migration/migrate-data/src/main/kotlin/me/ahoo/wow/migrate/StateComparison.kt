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

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventLog
import tools.jackson.databind.JsonNode

/**
 * The options of a comparison: [pageSize] snapshots per page, [concurrency] of them at a time, [sample] the percentage
 * of the instances compared (100 for all), and [restart] to compare again from the first instance. [fingerprint] is
 * what the result depends on ([ComparisonFingerprint]); [lookbackMillis] how far before the last comparison started
 * a comparison again reads what 9.x wrote: 9.x nodes' clocks differ from the tool's.
 */
internal data class CompareOptions(
    val pageSize: Int = 500,
    val concurrency: Int = 8,
    val sample: Int = ALL,
    val restart: Boolean = false,
    val fingerprint: String = "",
    val lookbackMillis: Long = 60_000,
) {
    init {
        require(pageSize >= 1) { "A page holds at least one snapshot." }
        require(concurrency >= 1) { "At least one snapshot at a time." }
        require(sample in 1..ALL) { "A sample is 1 to 100 percent of the instances, not $sample." }
        require(lookbackMillis >= 0) { "The look-back window is never negative." }
    }

    /**
     * Whether the instance [id] is in the sample: by the hash of its tenant and id, so every run samples the same
     * instances.
     */
    fun sampled(id: NineXAggregateId): Boolean = sample == ALL || Math.floorMod(id.toString().hashCode(), ALL) < sample

    companion object {
        const val ALL: Int = 100
    }
}

/**
 * The comparison of one 9.x aggregate so far: of its [instances] in the 9.x store, [snapshots] have a 9.x snapshot,
 * [compared] of those were compared (the sample) and [differing] differ, by the [kinds] of difference
 * ([FieldDifference.kind]) with the number of instances that have each. A comparison again gives [changed], the
 * instances changed on either side since the last comparison or found differing then, of which [recompared] had a
 * snapshot in the sample and were compared; [differing] and [kinds] are then those of every instance.
 */
internal data class ComparisonReport(
    val aggregate: NineXAggregate,
    val instances: Long,
    val snapshots: Long,
    val compared: Long,
    val differing: Long,
    val kinds: Map<String, Long>,
    val done: Boolean,
    val changed: Long? = null,
    val recompared: Long = 0,
) {
    /** Whether every instance compared equals its 9.x snapshot. */
    val passed: Boolean get() = differing == 0L

    fun render(): String = buildString {
        if (changed != null) {
            append("$aggregate compared again: $changed instances changed since the last comparison or differing ")
            append("then, $recompared compared; $differing differ in all")
        } else {
            append("$aggregate${if (done) " compared" else ""}: $snapshots of $instances instances have a 9.x snapshot")
            append(" (${"%.1f".format(snapshots * PERCENT / instances.coerceAtLeast(1))}%), ")
            append("$compared compared, $differing differ")
        }
        if (kinds.isNotEmpty()) {
            append("; differing fields: ")
            append(kinds.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} (${it.value})" })
        }
    }

    private companion object {
        const val PERCENT = 100.0
    }
}

/**
 * The state comparison (step 4): each 9.x snapshot of a migrated aggregate against the V10 state rebuilt
 * from the imported log at the snapshot's version, through the aggregate's V10 model.
 *
 * - **What is compared.** The state, by [StateDiff], the 9.x state read through the aggregate's
 *   [AggregateMapping.state]; the deleted mark (`$deleted`); the owner and the space (`$ownerId`, `$spaceId`), the
 *   9.x ones narrowed by the V10 ownership as the import narrows them. A version the V10 log does not hold is the
 *   difference `$version`. The 9.x tags the map maps to access attributes ([AggregateMapping.tags]): each against the
 *   attribute V10 derives from the rebuilt state (`$access.<attribute>`), both sides in the stored form.
 * - **Report.** Each instance that differs is printed with its aggregate id, version and fields
 *   ([ComparisonReport.render] sums them up); the share of instances with a snapshot is reported, since an instance
 *   without one cannot be compared.
 * - **Paging and checkpoints.** Snapshots are read [CompareOptions.pageSize] at a time in aggregate id order, compared
 *   [CompareOptions.concurrency] at a time, and the checkpoint ([CompareCheckpoint]) moves after each page, its
 *   counts kept, so a stopped comparison resumes and still reports what it found before.
 * - **Again, only what changed.** A full comparison records, before its first snapshot, where the V10 log ends
 *   ([EventLog.head]), the tool's clock, and the [CompareOptions.fingerprint]; as it goes, the instances that differ. Once it
 *   has completed, `compare` compares again only: the 9.x snapshots taken since, less [CompareOptions.lookbackMillis]
 *   ([NineXSnapshotSource.takenSince]); the instances 9.x committed streams of since, on the commit-time index
 *   ([NineXEventSource.committedSince]); those the V10 log holds entries of after the heads ([changedSince]); and those
 *   found differing. Every other instance compares as it did: one comparison reads the instance's 9.x snapshot, its V10
 *   log up to that version, which only grows, and what the fingerprint holds. Its result then stands for every
 *   instance, and it records where it started, as the full comparison did. It compares in full instead, saying why,
 *   when the checkpoint holds no positions (an earlier version's), the fingerprint changed, more than [MAX_RECORDED]
 *   instances differed, or the 9.x store lacks the commit-time index ([CommitTimeIndexed]).
 *   A comparison stopped half-way resumes only on the inputs it started with; one on another sample, or stopped by an
 *   earlier version or on other inputs, starts again from the first instance, saying why ([droppedBecause]).
 *
 * The full import of the aggregate must be complete. Nothing is written but the checkpoint.
 */
@Suppress("LongParameterList")
internal class StateComparison(
    private val snapshots: NineXSnapshotSource,
    private val events: NineXEventSource,
    private val plan: MigrationPlan,
    private val models: MigrationModels,
    private val logOf: (String) -> EventLog,
    private val checkpoints: CheckpointRepository,
    private val options: CompareOptions = CompareOptions(),
    private val report: (String) -> Unit = {},
    private val codec: EventCodec = EventCodec(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Compares the 9.x snapshots of [aggregate] not yet compared, or, once a full comparison has completed, those changed
     * since; the report of every instance.
     */
    suspend fun compare(aggregate: NineXAggregate): ComparisonReport {
        val aggregatePlan = plan.of(aggregate)
        if (checkpoints.load().of(aggregate)?.completed != true) {
            throw MigrationException("The full import of $aggregate has not completed: run the import before compare.")
        }
        val log = logOf(aggregatePlan.target.context)
        val target = Target(aggregatePlan, models.of(aggregatePlan.target), Kernel(log, codec), log)
        val instances = blocking { snapshots.instances(aggregate) }
        val saved = checkpoints.load().comparisonOf(aggregate)?.takeUnless { options.restart }?.let { saved ->
            val dropped = droppedBecause(saved) ?: return@let saved
            report("$aggregate is compared in full again from the first instance: $dropped.")
            null
        }
        if (saved?.completed == true) {
            againFrom(aggregate, saved)?.let { (since, recorded) ->
                return again(target, instances, saved, since, recorded).also { report(it.render()) }
            }
        }
        var checkpoint = saved?.takeUnless { it.completed } ?: started(log).let { since ->
            CompareCheckpoint(sample = options.sample, since = since, differs = emptyMap())
        }
        val permits = Semaphore(options.concurrency)
        while (!checkpoint.completed) {
            val page = blocking { snapshots.snapshots(aggregate, checkpoint.after, options.pageSize) }
            checkpoint = if (page.isEmpty()) {
                checkpoint.copy(completed = true)
            } else {
                comparedPage(target, page, permits, checkpoint)
            }
            checkpoints.update { it.with(aggregate, checkpoint) }
            if (!checkpoint.completed) {
                report(reportOf(aggregate, instances, checkpoint).render())
            }
        }
        return reportOf(aggregate, instances, checkpoint).also { report(it.render()) }
    }

    /** [checkpoint] moved past [page], its snapshots in the sample compared [CompareOptions.concurrency] at a time. */
    private suspend fun comparedPage(
        target: Target,
        page: List<NineXSnapshot>,
        permits: Semaphore,
        checkpoint: CompareCheckpoint,
    ): CompareCheckpoint {
        // An instance the map excludes has no V10 state to compare.
        val sampled = page.filter {
            !plan.excludes(target.plan.source, it.aggregateId.id) && options.sampled(it.aggregateId)
        }
        val differences = coroutineScope {
            sampled.map { snapshot ->
                async { permits.withPermit { snapshot.aggregateId.id to compare(target, snapshot) } }
            }.awaitAll()
        }.filter { it.second.isNotEmpty() }
        val kinds = differences.flatMap { (_, found) -> kindsOf(found) }
            .fold(checkpoint.kinds) { counts, kind -> counts + (kind to (counts[kind] ?: 0) + 1) }
        return checkpoint.copy(
            after = page.last().aggregateId,
            snapshots = checkpoint.snapshots + page.size,
            compared = checkpoint.compared + sampled.size,
            differing = checkpoint.differing + differences.size,
            kinds = kinds,
            differs = checkpoint.differs?.plus(differences.associate { (id, found) -> id to kindsOf(found) })
                ?.takeIf { it.size <= MAX_RECORDED },
        )
    }

    /**
     * Where the completed comparison [saved] of [aggregate] compares again from, with the instances it found differing;
     * or null when it compares in full, which is reported with the reason.
     */
    private fun againFrom(
        aggregate: NineXAggregate,
        saved: CompareCheckpoint,
    ): Pair<ComparisonStart, Map<String, List<String>>>? {
        val since = saved.since
        val recorded = saved.differs
        val inFull = when {
            since == null ->
                "the last comparison ran with an earlier version, which recorded no position to compare again from"

            since.fingerprint != options.fingerprint -> "$INPUTS_CHANGED since the last comparison"

            recorded == null -> "more than $MAX_RECORDED instances differed, too many to record"
            unindexed(aggregate) ->
                "the 9.x store has no commit-time index to read what 9.x committed since (the tail creates it)"

            else -> return since to recorded
        }
        report("$aggregate is compared in full again: $inFull.")
        return null
    }

    /**
     * Why the saved comparison [saved] does not stand for this one, which then starts again from the first instance;
     * null when it does. Another sample drops it whether it completed or not. One stopped half-way stands only for the
     * inputs it started with: an earlier version recorded none, and its fingerprint may differ. What a completed one
     * stands for, [againFrom] says.
     */
    private fun droppedBecause(saved: CompareCheckpoint): String? {
        val since = saved.since
        return when {
            saved.sample != options.sample -> "the sample changed from ${saved.sample}% to ${options.sample}%"
            saved.completed -> null
            since == null -> "it was stopped by an earlier version, which recorded no inputs"
            since.fingerprint != options.fingerprint -> "$INPUTS_CHANGED since it stopped"
            else -> null
        }
    }

    /** Whether the 9.x store lacks the commit-time index [aggregate]'s comparison again reads what 9.x committed by. */
    private fun unindexed(aggregate: NineXAggregate): Boolean =
        (events as? CommitTimeIndexed)?.missingCommitTimeIndexes(listOf(aggregate)).orEmpty().isNotEmpty()

    /** Where a comparison on [log] starts: its heads, then the tool's clock, and the options' fingerprint. */
    private suspend fun started(log: EventLog): ComparisonStart =
        log.heads().let { heads -> ComparisonStart(heads, clock(), options.fingerprint) }

    /**
     * Compares again the instances of [saved]'s aggregate changed on either side since it started ([since]), and those
     * it found differing ([recorded]), then records where this comparison started and what differs now.
     */
    private suspend fun again(
        target: Target,
        instances: Long,
        saved: CompareCheckpoint,
        since: ComparisonStart,
        recorded: Map<String, List<String>>,
    ): ComparisonReport {
        val aggregate = target.plan.source
        val start = started(target.log)
        val from = since.at - options.lookbackMillis
        val taken = takenSince(aggregate, from)
        val ids = LinkedHashSet(taken.keys)
        ids += committedSince(aggregate, from)
        target.log.changedSince(target.plan.target, since.heads, start.heads).forEach { ids += it.id }
        ids += recorded.keys
        val changed = ids.filterNot { plan.excludes(aggregate, it) }
        val permits = Semaphore(options.concurrency)
        val compared = coroutineScope {
            changed.map { id ->
                async {
                    permits.withPermit {
                        val snapshot = taken[id] ?: blocking { snapshots.snapshot(aggregate, id) }
                        snapshot?.takeIf { options.sampled(it.aggregateId) }
                            ?.let { id to compare(target, it) }
                    }
                }
            }.awaitAll()
        }.filterNotNull()
        val differs = recorded - changed.toSet() +
            compared.filter { it.second.isNotEmpty() }.associate { (id, found) -> id to kindsOf(found) }
        val kinds = differs.values.flatten().groupingBy { it }.eachCount().mapValues { it.value.toLong() }
        checkpoints.update {
            it.with(
                aggregate,
                saved.copy(
                    differing = differs.size.toLong(),
                    kinds = kinds,
                    since = start,
                    differs = differs.takeIf { found -> found.size <= MAX_RECORDED },
                ),
            )
        }
        return ComparisonReport(
            aggregate = aggregate,
            instances = instances,
            snapshots = saved.snapshots,
            compared = saved.compared,
            differing = differs.size.toLong(),
            kinds = kinds,
            done = true,
            changed = changed.size.toLong(),
            recompared = compared.size.toLong(),
        )
    }

    /** The 9.x snapshots of [aggregate] taken at or after [from], by aggregate id. */
    private suspend fun takenSince(aggregate: NineXAggregate, from: Long): Map<String, NineXSnapshot> {
        val taken = LinkedHashMap<String, NineXSnapshot>()
        var after: String? = null
        while (true) {
            val page = blocking { snapshots.takenSince(aggregate, from, after, options.pageSize) }
            if (page.isEmpty()) {
                return taken
            }
            page.forEach { taken[it.aggregateId.id] = it }
            after = page.last().aggregateId.id
        }
    }

    /** The ids of [aggregate]'s instances 9.x committed streams of at or after [from]. */
    private suspend fun committedSince(aggregate: NineXAggregate, from: Long): Set<String> {
        val ids = LinkedHashSet<String>()
        var after: NineXPosition? = null
        while (true) {
            val page = blocking { events.committedSince(aggregate, from, after, options.pageSize) }
            if (page.isEmpty()) {
                return ids
            }
            page.forEach { ids += it.aggregateId.id }
            after = page.last().let { NineXPosition(it.createTime, it.id) }
        }
    }

    private fun kindsOf(differences: List<FieldDifference>): List<String> = differences.map { it.kind }.distinct()

    /** The differences of [snapshot] from the V10 state at its version; empty when equal. */
    private suspend fun compare(target: Target, snapshot: NineXSnapshot): List<FieldDifference> {
        val ref = target.plan.target.ref(snapshot.aggregateId.tenantId, snapshot.aggregateId.id)
        val streams = target.log.read(ref, 1, snapshot.version).toList()
        val rebuilt = streams.takeIf { it.lastOrNull()?.version == snapshot.version }
            ?.let { target.kernel.replayed(target.model, ref, it) }
        val differences = if (rebuilt == null) {
            listOf(FieldDifference(VERSION, node(snapshot.version), streams.lastOrNull()?.version?.let(::node)))
        } else {
            differencesOf(target.plan, snapshot, rebuilt) + accessDifferences(target, snapshot, rebuilt)
        }
        if (differences.isNotEmpty()) {
            report(
                "DIFFERS ${snapshot.aggregate} ${snapshot.aggregateId} v${snapshot.version}: " +
                    differences.joinToString("; ") { it.render() },
            )
        }
        return differences
    }

    private fun differencesOf(plan: AggregatePlan, snapshot: NineXSnapshot, rebuilt: CachedState): List<FieldDifference> {
        val scope = plan.ownership.scopeOf(Scope(snapshot.ownerId.ifBlank { null }, snapshot.spaceId.ifBlank { null }))
        val metadata = listOf(
            Triple(DELETED, node(snapshot.deleted), node(rebuilt.deleted)),
            Triple(OWNER_ID, node(scope.ownerId), node(rebuilt.scope.ownerId)),
            Triple(SPACE_ID, node(scope.spaceId), node(rebuilt.scope.spaceId)),
        ).filter { (_, nineX, v10) -> nineX != v10 }.map { (path, nineX, v10) ->
            FieldDifference(path, nineX.takeUnless { it.isNull }, v10.takeUnless { it.isNull })
        }
        // A 9.x tag became an access attribute, a field of the V10 state 9.x held as metadata: $access compares it.
        val attributes = plan.mapping.tags.mapNotNull { it.to }
        val state = plan.mapping.state.let { it.copy(ignore = it.ignore + attributes) }
        return metadata + StateDiff.diff(snapshot.state, codec.stateTree(rebuilt.state), state)
    }

    /**
     * The differences of [snapshot]'s 9.x tags from [rebuilt]'s access attributes (`$access.<attribute>`), for the keys
     * [AggregateMapping.tags] maps: each side the same way, the 9.x values converted as the import converts them
     * ([TagValues]), then both in the stored form ([AccessValues]), so the order of a multiple attribute's values, or a
     * key 9.x left absent where V10 holds none, is no difference. An attribute the Manifest does not declare is the
     * check's problem, not compared.
     */
    private fun accessDifferences(
        target: Target,
        snapshot: NineXSnapshot,
        rebuilt: CachedState
    ): List<FieldDifference> {
        val tags = target.plan.mapping.tags.filter { it.to != null }
        if (tags.isEmpty()) {
            return emptyList()
        }
        val v10 = AccessValues.normalized(target.model.accessOf(rebuilt.state))
        return tags.mapNotNull { tag ->
            val name = tag.to!!
            val attribute = target.plan.accessOf(name) ?: return@mapNotNull null
            val values = snapshot.tags[tag.key]
            val nineX = try {
                AccessValues.normalized(mapOf(name to TagValues.valueOf(attribute, values)))[name]
            } catch (_: TagValueException) {
                values
            }
            FieldDifference("$ACCESS.$name", nineX?.let(::node), v10[name]?.let(::node)).takeIf { nineX != v10[name] }
        }
    }

    private fun reportOf(aggregate: NineXAggregate, instances: Long, checkpoint: CompareCheckpoint) = ComparisonReport(
        aggregate = aggregate,
        instances = instances,
        snapshots = checkpoint.snapshots,
        compared = checkpoint.compared,
        differing = checkpoint.differing,
        kinds = checkpoint.kinds,
        done = checkpoint.completed,
    )

    private fun node(value: Any?): JsonNode =
        value?.let { WowJson.mapper.valueToTree<JsonNode>(it) } ?: WowJson.mapper.nodeFactory.nullNode()

    /** What one aggregate's states are rebuilt from and compared with: its plan, its V10 model and its log. */
    private class Target(val plan: AggregatePlan, val model: AggregateModel<Any>, val kernel: Kernel, val log: EventLog)

    companion object {
        /**
         * The most differing instances a comparison records, by id, to compare them again: the checkpoint is written
         * after every page, so a comparison that finds more compares in full the next time.
         */
        const val MAX_RECORDED: Int = 1000
        private const val VERSION = "\$version"
        private const val DELETED = "\$deleted"
        private const val OWNER_ID = "\$ownerId"
        private const val SPACE_ID = "\$spaceId"
        const val ACCESS = "\$access"

        /** What a comparison's fingerprint holds, the inputs whose change makes an earlier result not stand. */
        private const val INPUTS_CHANGED =
            "the map, a Manifest, the classpath (the V10 models), the sample, the time zone or the tool changed"
    }
}
