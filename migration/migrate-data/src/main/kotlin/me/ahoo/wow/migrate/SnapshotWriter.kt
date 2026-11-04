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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.access.AccessSchema
import me.ahoo.wow.runtime.access.AccessStampOptions
import me.ahoo.wow.runtime.access.stampAllIn
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.snapshot.SnapshotStore

/**
 * The snapshot writing of one 9.x aggregate since it started: [written] V10 snapshots written, [skipped] already in the
 * store at their version, and the instances whose snapshot [failed]; when [caughtUp], those of one catch-up of a
 * completed walk alone: the instances the V10 log changed since.
 */
internal data class SnapshotProgress(
    val aggregate: NineXAggregate,
    val written: Long,
    val skipped: Long,
    val done: Boolean,
    val failed: Long = 0,
    val caughtUp: Boolean = false,
) {
    fun render(): String {
        val count = written + skipped + failed
        val instances = when {
            caughtUp -> " caught up: $count instances the V10 log changed since"
            done -> " done: $count instances"
            else -> ": $count instances"
        }
        return "$aggregate snapshots$instances, $written V10 snapshots written, $skipped already there" +
            if (failed > 0) ", $failed failed" else ""
    }
}

/**
 * Writes the V10 snapshot of every imported instance (step 5), so the V10 service loads the aggregates
 * without replaying their logs from the first stream: each instance's V10 log replayed through the aggregate's V10
 * model ([Kernel.replayed]), written as the snapshot of the log's last version into the context's snapshot store, on
 * the same backend as the log. The snapshot is the one the V10 snapshot subscription would write: the state, the
 * scope, the deleted mark and schedules, the commit times and the operators of the first and the last stream.
 *
 * - **Stamped.** The instance's streams get the access attributes of the state it rebuilt, as of the log's last version
 *   ([AccessStamps.stamp]), as the V10 nodes' access walk would stamp them, so an events query under an access policy
 *   reads the imported history once V10 serves. The stamps of a page of instances are written together
 *   ([AccessStamps.stampAll]), a catch-up's too, a page at a time. A stream stamped as of a later version keeps its attributes, and one
 *   appended since is stamped by the next catch-up. A type every 9.x aggregate mapped to it stamped without a failure
 *   has its walk recorded as completed ([recordWalks]): the nodes do not walk it again when they start or are
 *   activated.
 * - **Idempotent.** [SnapshotStore.save] writes no snapshot of a version the store already holds, so a run again
 *   skips them; a snapshot written since at a higher version stays.
 * - **Checkpointed.** Instances are walked as the import walks them ([NineXEventSource.aggregateIds]), a page of
 *   [ImportOptions.pageSize] at a time, [ImportOptions.concurrency] at a time, and the checkpoint
 *   ([SnapshotCheckpoint]) moves after each page; [ImportOptions.restart] walks every instance again. An instance the
 *   map excludes ([MigrationPlan.excludes]) has no history in the V10 log, and is passed over.
 * - **Caught up.** A walk records where the V10 log ends, by partition ([EventLog.head]), before its first instance.
 *   Once it has completed, a run catches up instead: it reads the log's entries after those heads, up to the heads it
 *   reads first, writes the snapshot of each instance of the aggregate they hold, as the walk does, and records the
 *   heads it read. So the snapshots written during the dual-run need, at the cut-over, only the instances the log
 *   changed since. An instance whose log did not change has the history its snapshot was written from: the log only
 *   grows. A model change between two runs is not covered, by the walk as by the catch-up, as [SnapshotStore.save]
 *   never writes again a version the store holds. A snapshot is a cache of the log ([SnapshotStore]): one an
 *   append in flight leaves behind (MongoDB's head may count a sequence above one still landing) only makes
 *   the host replay that instance's log after it. A completed walk without heads (an earlier version's) is walked
 *   again.
 * - **Every failure reported.** An instance whose state does not rebuild, an event its model does not read or an
 *   instance missing from the log, which 9.x wrote after the import and the tail brings in, is [report]ed with the
 *   reason, and the walk goes on. A walk that met any ends
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
    /** The most streams one stamp of an instance's history writes. */
    private val batch: Int = AccessStampOptions.DEFAULT_BATCH,
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
        val saved = checkpoints.load().snapshotsOf(aggregate)?.takeUnless { options.restart }
        if (saved?.completed == true) {
            saved.heads?.let { return catchUp(target, saved, it).also(progress) }
            report(
                "$aggregate: its snapshots were written by an earlier version, which recorded no position of the V10 " +
                    "log to catch up from, so every instance is walked again.",
            )
        }
        var checkpoint = saved?.takeUnless { it.completed } ?: target.log.heads().let { SnapshotCheckpoint(heads = it) }
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
                val written = writePage(target, page.filterNot { plan.excludes(aggregate, it.id) }, permits)
                checkpoint = checkpoint.copy(
                    after = page.last(),
                    written = checkpoint.written + written.count { it?.saved == true },
                    skipped = checkpoint.skipped + written.count { it?.saved == false },
                    failed = checkpoint.failed + written.count { it == null },
                )
                checkpoints.update { it.with(aggregate, checkpoint) }
                progress(progressOf(aggregate, checkpoint))
            }
        }
        return progressOf(aggregate, checkpoint).also(progress)
    }

    /**
     * Writes the snapshots of the instances of [target] the V10 log holds entries of after [recorded], [checkpoint]'s
     * heads, up to the heads read first, [ImportOptions.concurrency] at a time, then records those heads. One that
     * fails is reported and leaves the heads where they were, so the next run reads the same entries again.
     */
    private suspend fun catchUp(
        target: Target,
        checkpoint: SnapshotCheckpoint,
        recorded: Map<Int, Long>,
    ): SnapshotProgress {
        val heads = target.log.heads()
        val changed = target.log.changedSince(target.plan.target, recorded, heads)
            .filterNot { plan.excludes(target.aggregate, it.id) }
        val permits = Semaphore(options.concurrency)
        // A page at a time, as the walk writes them: the stamps of a page are written together.
        val written = changed.chunked(options.pageSize).flatMap { refs ->
            writePage(target, refs.map { NineXAggregateId(it.id, it.tenantId) }, permits)
        }
        val progress = SnapshotProgress(
            aggregate = target.aggregate,
            written = written.count { it?.saved == true }.toLong(),
            skipped = written.count { it?.saved == false }.toLong(),
            done = true,
            failed = written.count { it == null }.toLong(),
            caughtUp = true,
        )
        checkpoints.update {
            it.with(
                target.aggregate,
                checkpoint.copy(
                    written = checkpoint.written + progress.written,
                    skipped = checkpoint.skipped + progress.skipped,
                    heads = if (progress.failed == 0L) heads else recorded,
                ),
            )
        }
        return progress
    }

    /**
     * Writes the snapshots of [ids], instances of [target], [permits] at a time, then stamps their histories together,
     * at most [batch] streams a write ([stampAllIn]); what [write] gave each.
     */
    private suspend fun writePage(target: Target, ids: List<NineXAggregateId>, permits: Semaphore): List<Written?> {
        val written = coroutineScope {
            ids.map { id -> async { permits.withPermit { write(target, id) } } }.awaitAll()
        }
        target.stamps?.stampAllIn(written.mapNotNull { it?.stamp }, batch)
        return written
    }

    /** An instance's snapshot [saved] (false: the store held it already), and the [stamp] of its history. */
    private class Written(val saved: Boolean, val stamp: AccessStamp)

    /**
     * Writes the snapshot of the instance [id] of [target], and gives the stamp of its history with the attributes of
     * its state as of its last version; null when its state does not rebuild, which is [report]ed.
     */
    private suspend fun write(target: Target, id: NineXAggregateId): Written? {
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
            // The full import completed, so 9.x wrote the instance after it.
            report(
                "FAILED $subject has no complete history in the V10 log: 9.x wrote it after the import, so run the " +
                    "tail (tail --once), then snapshot again.",
            )
            return null
        }
        val first = streams.first()
        val last = streams.last()
        val access = codec.accessOf(target.model, state.state)
        val stamp = AccessStamp(ref, AccessValues.ofStream(access, state.scope), last.version, 1, last.version + 1)
        val saved = target.store.save(
            codec.snapshotOf(
                target.model,
                ref,
                codec.stateText(state.state),
                last.version,
                state.scope,
                first.commitTime,
                last.commitTime,
                state.deleted,
                last.operator,
                first.operator,
                state.schedules,
                access,
            ),
        )
        return Written(saved, stamp)
    }

    /**
     * Records the access walk of each V10 type whose every 9.x aggregate's snapshots [progress] holds as done without a
     * failure, as completed for the type's access schema ([AccessSchema]), so the V10 nodes find it walked: [writePage]
     * stamped each instance's history. It records no heads, so a stream without attributes, one those stamps did not
     * reach (an append in flight can land on MongoDB below the head a walk read) or one appended since, is the built-in
     * subscription `wow.access`'s, which stamps its aggregate when it reads it. A walk already recorded is left as it is,
     * and a node whose model has another access schema walks the type again.
     */
    suspend fun recordWalks(progress: List<SnapshotProgress>) {
        val clean = progress.filter { it.done && it.failed == 0L }.associateBy { it.aggregate }
        plan.aggregates.keys.groupBy { plan.of(it).target }.forEach { (type, aggregates) ->
            if (aggregates.all { it in clean }) {
                recordWalk(type, aggregates.sumOf { clean.getValue(it).written + clean.getValue(it).skipped })
            }
        }
    }

    private suspend fun recordWalk(type: AggregateType, walked: Long) {
        val walks = logOf(type.context) as? AccessWalks ?: return
        if (!walks.holds(type)) {
            return
        }
        val schema = AccessSchema.of(models.of(type))
        val walk = AccessWalk(schema, emptyMap(), walked = walked, completed = true)
        if (walks.claim(type, walk, expected = null)) {
            report(
                "$type: its access walk is recorded as completed for the schema [$schema]: $walked instances stamped."
            )
            return
        }
        val recorded = walks.walk(type) ?: return
        if (recorded.schema != schema) {
            report(
                "$type: the log records its access walk for the schema [${recorded.schema}], not this model's " +
                    "[$schema]: it is left as it is, and the V10 nodes of this model walk the type again.",
            )
        }
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

        /** Where the log's history is stamped; none for a log that keeps no access attributes. */
        val stamps: AccessStamps? = log as? AccessStamps
    }

    private fun progressOf(aggregate: NineXAggregate, checkpoint: SnapshotCheckpoint) =
        SnapshotProgress(aggregate, checkpoint.written, checkpoint.skipped, checkpoint.completed, checkpoint.failed)
}
