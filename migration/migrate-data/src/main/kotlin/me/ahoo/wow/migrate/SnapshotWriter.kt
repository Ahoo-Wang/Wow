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
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.snapshot.SnapshotStore

/**
 * The snapshot writing of one 9.x aggregate since it started: [written] V10 snapshots written, [skipped] already in the
 * store at their version, and the instances whose snapshot [failed].
 */
internal data class SnapshotProgress(
    val aggregate: NineXAggregate,
    val written: Long,
    val skipped: Long,
    val done: Boolean,
    val failed: Long = 0,
) {
    fun render(): String =
        "$aggregate snapshots${if (done) " done" else ""}: ${written + skipped + failed} instances, $written V10 " +
            "snapshots written, $skipped already there${if (failed > 0) ", $failed failed" else ""}"
}

/**
 * Writes the V10 snapshot of every imported instance (step 5), so the V10 service loads the aggregates
 * without replaying their logs from the first stream: each instance's V10 log replayed through the aggregate's V10
 * model ([Kernel.replayed]), written as the snapshot of the log's last version into the context's snapshot store, on
 * the same backend as the log. The snapshot is the one the V10 snapshot subscription would write: the state, the
 * scope, the deleted mark, the tags and schedules, the commit times and the operators of the first and the last stream.
 *
 * - **Idempotent.** [SnapshotStore.save] writes no snapshot of a version the store already holds, so a run again
 *   skips them; a snapshot written since at a higher version stays.
 * - **Checkpointed.** Instances are walked as the import walks them ([NineXEventSource.aggregateIds]), a page of
 *   [ImportOptions.pageSize] at a time, [ImportOptions.concurrency] at a time, and the checkpoint
 *   ([SnapshotCheckpoint]) moves after each page; [ImportOptions.restart] walks every instance again. An instance the
 *   map excludes ([MigrationPlan.excludes]) has no history in the V10 log, and is passed over.
 * - **Every failure reported.** An instance whose state does not rebuild, an event its model does not read or an
 *   instance missing from the log, is [report]ed with the reason, and the walk goes on. A walk that met any ends
 *   without completing ([SnapshotProgress.failed]), and starts from the first instance at the next run, which passes
 *   over the snapshots written.
 *
 * The full import of the aggregate must be complete. The host still claims and backfills the snapshot tables:
 * its snapshot subscription then finds each snapshot written and passes over it.
 */
@Suppress("LongParameterList")
internal class SnapshotWriter(
    private val source: NineXEventSource,
    private val plan: MigrationPlan,
    private val models: MigrationModels,
    private val logOf: (String) -> EventLog,
    private val storeOf: (String) -> SnapshotStore,
    private val checkpoints: CheckpointRepository,
    private val options: ImportOptions = ImportOptions(),
    private val progress: (SnapshotProgress) -> Unit = {},
    private val codec: EventCodec = EventCodec(),
    private val report: (String) -> Unit = {},
) {
    /**
     * Writes the snapshots of [aggregate]'s instances not yet written, and marks its snapshot writing complete, unless
     * one failed.
     */
    suspend fun writeAll(aggregate: NineXAggregate): SnapshotProgress {
        val aggregatePlan = plan.of(aggregate)
        if (checkpoints.load().of(aggregate)?.completed != true) {
            throw MigrationException(
                "The full import of $aggregate has not completed: run the import before writing snapshots.",
            )
        }
        val target = Target(
            aggregate,
            aggregatePlan,
            models.of(aggregatePlan.target),
            logOf(aggregatePlan.target.context),
            storeOf(aggregatePlan.target.context),
        )
        var checkpoint = checkpoints.load().snapshotsOf(aggregate)?.takeUnless { options.restart } ?: SnapshotCheckpoint()
        val permits = Semaphore(options.concurrency)
        while (!checkpoint.completed) {
            val page = withContext(Dispatchers.IO) {
                source.aggregateIds(aggregate, checkpoint.after, options.pageSize)
            }
            if (page.isEmpty()) {
                if (checkpoint.failed > 0) {
                    // The next run walks every instance again: it passes over the snapshots written.
                    checkpoints.update { it.with(aggregate, SnapshotCheckpoint()) }
                    return progressOf(aggregate, checkpoint).also(progress)
                }
                checkpoint = checkpoint.copy(completed = true)
                checkpoints.update { it.with(aggregate, checkpoint) }
            } else {
                val written = coroutineScope {
                    page.filterNot { plan.excludes(aggregate, it.id) }
                        .map { id -> async { permits.withPermit { write(target, id) } } }.awaitAll()
                }
                checkpoint = checkpoint.copy(
                    after = page.last(),
                    written = checkpoint.written + written.count { it == true },
                    skipped = checkpoint.skipped + written.count { it == false },
                    failed = checkpoint.failed + written.count { it == null },
                )
                checkpoints.update { it.with(aggregate, checkpoint) }
                progress(progressOf(aggregate, checkpoint))
            }
        }
        return progressOf(aggregate, checkpoint).also(progress)
    }

    /**
     * Writes the snapshot of the instance [id] of [target]; whether it was written (false: the store holds it), or
     * null when its state does not rebuild, which is [report]ed.
     */
    private suspend fun write(target: Target, id: NineXAggregateId): Boolean? {
        val ref = target.plan.target.ref(id.tenantId, id.id)
        val subject = "$ref, the 9.x instance ${target.aggregate}/$id"
        val streams = target.log.read(ref).toList()
        val state = try {
            target.kernel.replayed(target.model, ref, streams)
        } catch (@Suppress("TooGenericExceptionCaught") unreadable: RuntimeException) {
            // An event the model does not read, or an evolve function that fails, as the kernel reports it.
            report("FAILED $subject: ${reasonOf(unreadable)}")
            return null
        }
        if (state == null) {
            report("FAILED $subject has no complete history in the V10 log: run the import first.")
            return null
        }
        val first = streams.first()
        val last = streams.last()
        return target.store.save(
            codec.snapshotOf(
                target.model,
                ref,
                codec.stateText(state.state),
                last.version,
                state.scope,
                first.commitTime,
                last.commitTime,
                state.deleted,
                state.tags,
                last.operator,
                first.operator,
                state.schedules,
            ),
        )
    }

    /** Where the snapshots of the 9.x [aggregate] are rebuilt from and written to. */
    private inner class Target(
        val aggregate: NineXAggregate,
        val plan: AggregatePlan,
        val model: AggregateModel<Any>,
        val log: EventLog,
        val store: SnapshotStore,
    ) {
        val kernel = Kernel(log, codec)
    }

    private fun progressOf(aggregate: NineXAggregate, checkpoint: SnapshotCheckpoint) =
        SnapshotProgress(aggregate, checkpoint.written, checkpoint.skipped, checkpoint.completed, checkpoint.failed)
}
