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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventLog
import tools.jackson.databind.JsonNode

/**
 * The options of a comparison: [pageSize] snapshots per page, [concurrency] of them at a time, [sample] the percentage
 * of the instances compared (100 for all), and [restart] to compare again from the first instance.
 */
data class CompareOptions(
    val pageSize: Int = 500,
    val concurrency: Int = 8,
    val sample: Int = ALL,
    val restart: Boolean = false,
) {
    init {
        require(pageSize >= 1) { "A page holds at least one snapshot." }
        require(concurrency >= 1) { "At least one snapshot at a time." }
        require(sample in 1..ALL) { "A sample is 1 to 100 percent of the instances, not $sample." }
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
 * ([FieldDifference.kind]) with the number of instances that have each.
 */
data class ComparisonReport(
    val aggregate: NineXAggregate,
    val instances: Long,
    val snapshots: Long,
    val compared: Long,
    val differing: Long,
    val kinds: Map<String, Long>,
    val done: Boolean,
) {
    /** Whether every instance compared equals its 9.x snapshot. */
    val passed: Boolean get() = differing == 0L

    fun render(): String = buildString {
        append("$aggregate${if (done) " compared" else ""}: $snapshots of $instances instances have a 9.x snapshot")
        append(" (${"%.1f".format(snapshots * PERCENT / instances.coerceAtLeast(1))}%), ")
        append("$compared compared, $differing differ")
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
 * The state comparison (design 07 §3 step 4): each 9.x snapshot of a migrated aggregate against the V10 state rebuilt
 * from the imported log at the snapshot's version, through the aggregate's V10 model.
 *
 * - **What is compared.** The state, by [StateDiff], the 9.x state read through the aggregate's
 *   [AggregateMapping.state]; the deleted mark (`$deleted`); the owner and the space (`$ownerId`, `$spaceId`), the
 *   9.x ones narrowed by the V10 ownership as the import narrows them (decision D189); and the tags (`$tags`). A
 *   version the V10 log does not hold is the difference `$version`.
 * - **Report.** Each instance that differs is printed with its aggregate id, version and fields
 *   ([ComparisonReport.render] sums them up); the share of instances with a snapshot is reported, since an instance
 *   without one cannot be compared.
 * - **Paging and checkpoints.** Snapshots are read [CompareOptions.pageSize] at a time in aggregate id order, compared
 *   [CompareOptions.concurrency] at a time, and the checkpoint ([CompareCheckpoint]) moves after each page, its
 *   counts kept, so a stopped comparison resumes and still reports what it found before.
 *
 * The full import of the aggregate must be complete. Nothing is written but the checkpoint.
 */
@Suppress("LongParameterList")
class StateComparison(
    private val snapshots: NineXSnapshotSource,
    private val plan: MigrationPlan,
    private val models: MigrationModels,
    private val logOf: (String) -> EventLog,
    private val checkpoints: CheckpointRepository,
    private val options: CompareOptions = CompareOptions(),
    private val report: (String) -> Unit = {},
    private val codec: EventCodec = EventCodec(),
) {
    /** Compares the 9.x snapshots of [aggregate] not yet compared; the report of every run since it started. */
    suspend fun compare(aggregate: NineXAggregate): ComparisonReport {
        val aggregatePlan = plan.of(aggregate)
        if (checkpoints.load().of(aggregate)?.completed != true) {
            throw MigrationException("The full import of $aggregate has not completed: run the import before compare.")
        }
        val model = models.of(aggregatePlan.target)
        val log = logOf(aggregatePlan.target.context)
        val kernel = Kernel(log, codec)
        val instances = blocking { snapshots.instances(aggregate) }
        var checkpoint = checkpoints.load().comparisonOf(aggregate)
            ?.takeUnless { options.restart || it.sample != options.sample }
            ?: CompareCheckpoint(sample = options.sample)
        val permits = Semaphore(options.concurrency)
        while (!checkpoint.completed) {
            val page = blocking { snapshots.snapshots(aggregate, checkpoint.after, options.pageSize) }
            if (page.isEmpty()) {
                checkpoint = checkpoint.copy(completed = true)
            } else {
                val sampled = page.filter { options.sampled(it.aggregateId) }
                val differences = coroutineScope {
                    sampled.map { snapshot ->
                        async { permits.withPermit { compare(aggregatePlan, model, kernel, log, snapshot) } }
                    }.awaitAll()
                }.filter { it.isNotEmpty() }
                val kinds = differences.flatMap { found -> found.map { it.kind }.distinct() }
                    .fold(checkpoint.kinds) { counts, kind -> counts + (kind to (counts[kind] ?: 0) + 1) }
                checkpoint = checkpoint.copy(
                    after = page.last().aggregateId,
                    snapshots = checkpoint.snapshots + page.size,
                    compared = checkpoint.compared + sampled.size,
                    differing = checkpoint.differing + differences.size,
                    kinds = kinds,
                )
            }
            checkpoints.update { it.with(aggregate, checkpoint) }
            if (!checkpoint.completed) {
                report(reportOf(aggregate, instances, checkpoint).render())
            }
        }
        return reportOf(aggregate, instances, checkpoint).also { report(it.render()) }
    }

    /** The differences of [snapshot] from the V10 state at its version; empty when equal. */
    private suspend fun compare(
        plan: AggregatePlan,
        model: AggregateModel<Any>,
        kernel: Kernel,
        log: EventLog,
        snapshot: NineXSnapshot,
    ): List<FieldDifference> {
        val ref = plan.target.ref(snapshot.aggregateId.tenantId, snapshot.aggregateId.id)
        val streams = log.read(ref, 1, snapshot.version).toList()
        val rebuilt = streams.takeIf { it.lastOrNull()?.version == snapshot.version }
            ?.let { kernel.replayed(model, ref, it) }
        val differences = if (rebuilt == null) {
            listOf(FieldDifference(VERSION, node(snapshot.version), streams.lastOrNull()?.version?.let(::node)))
        } else {
            differencesOf(plan, snapshot, rebuilt)
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
            Triple(TAGS, node(snapshot.tags), node(rebuilt.tags)),
        ).filter { (_, nineX, v10) -> nineX != v10 }.map { (path, nineX, v10) ->
            FieldDifference(path, nineX.takeUnless { it.isNull }, v10.takeUnless { it.isNull })
        }
        return metadata + StateDiff.diff(snapshot.state, codec.stateTree(rebuilt.state), plan.mapping.state)
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

    private suspend fun <T> blocking(read: () -> T): T = withContext(Dispatchers.IO) { read() }

    private companion object {
        const val VERSION = "\$version"
        const val DELETED = "\$deleted"
        const val OWNER_ID = "\$ownerId"
        const val SPACE_ID = "\$spaceId"
        const val TAGS = "\$tags"
    }
}
