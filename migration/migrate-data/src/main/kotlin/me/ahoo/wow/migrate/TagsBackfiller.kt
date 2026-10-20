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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.TagsBackfill
import kotlin.time.TimeSource

/**
 * Where the tags backfill of one V10 aggregate type stands (`backfill-tags`, decision D259): [afterTenantId] and
 * [afterId] name the last aggregate whose streams were backfilled, in the log's key order (aggregate id, then
 * tenant); [completed] is set once none is left; [aggregates], [streams] and [millis] are those of every run since it
 * started: how many aggregates were replayed, how many streams got their tags, and how long it took.
 */
data class TagsCheckpoint(
    val afterTenantId: String? = null,
    val afterId: String? = null,
    val completed: Boolean = false,
    val aggregates: Long = 0,
    val streams: Long = 0,
    val millis: Long = 0,
) {
    /** The aggregate a resumed walk starts after; null from the first. */
    fun after(type: AggregateType): AggregateRef? = afterId?.let { type.ref(checkNotNull(afterTenantId), it) }
}

/** The tags backfill of one V10 aggregate type since it started; see [TagsCheckpoint]. */
data class TagsProgress(val type: AggregateType, val checkpoint: TagsCheckpoint) {
    fun render(): String {
        val perAggregate = if (checkpoint.aggregates == 0L) 0.0 else checkpoint.millis.toDouble() / checkpoint.aggregates
        return "$type tags${if (checkpoint.completed) " done" else ""}: ${checkpoint.aggregates} aggregates replayed, " +
            "${checkpoint.streams} streams tagged, ${checkpoint.millis} ms (${"%.2f".format(perAggregate)} ms per " +
            "aggregate)"
    }
}

/**
 * Backfills the tags of the streams a V10 log appended before format 1 learned them (decision D259, R4-Q2c): each
 * aggregate with such a stream is replayed through its V10 model ([Kernel.evolved]), and every stream of it that
 * records no tags gets the tags the aggregate had after it, as the kernel records them at a commit.
 *
 * - **Only the missing.** A stream that records tags is never changed ([TagsBackfill.recordTags]), so a run again,
 *   or a run while the service appends, writes nothing twice.
 * - **Resumable.** Aggregates are walked in the log's key order, a page of [ImportOptions.pageSize] at a time,
 *   [ImportOptions.concurrency] at a time, and the checkpoint ([TagsCheckpoint]) moves after each page;
 *   [ImportOptions.restart] walks from the first again.
 * - **Elasticsearch after.** An events index written before the tags holds documents without them: re-sink it only
 *   once every log is backfilled ([untagged] says none is left).
 */
class TagsBackfiller(
    private val models: MigrationModels,
    private val logOf: (String) -> EventLog,
    private val checkpoints: CheckpointRepository,
    private val options: ImportOptions = ImportOptions(),
    private val progress: (TagsProgress) -> Unit = {},
    private val codec: EventCodec = EventCodec(),
) {
    /** Backfills the streams of [type] that record no tags, and marks its backfill complete. */
    suspend fun backfill(type: AggregateType): TagsProgress {
        val log = logOf(type.context)
        val backfill = log as? TagsBackfill ?: throw MigrationException(
            "The V10 log of ${type.context} records no tags to backfill: only PostgreSQL and MongoDB logs do.",
        )
        val model = models.of(type)
        val kernel = Kernel(log, codec)
        var checkpoint = checkpoints.load().tagsOf(type)?.takeUnless { options.restart } ?: TagsCheckpoint()
        val permits = Semaphore(options.concurrency)
        while (!checkpoint.completed) {
            val started = TimeSource.Monotonic.markNow()
            val page = backfill.untagged(type, checkpoint.after(type), options.pageSize)
            checkpoint = if (page.isEmpty()) {
                checkpoint.copy(completed = true)
            } else {
                val tagged = coroutineScope {
                    page.map { ref -> async { permits.withPermit { backfill(kernel, model, log, backfill, ref) } } }
                        .awaitAll()
                }
                checkpoint.copy(
                    afterTenantId = page.last().tenantId,
                    afterId = page.last().id,
                    aggregates = checkpoint.aggregates + page.size,
                    streams = checkpoint.streams + tagged.sum(),
                    millis = checkpoint.millis + started.elapsedNow().inWholeMilliseconds,
                )
            }
            checkpoints.update { it.with(type, checkpoint) }
            if (!checkpoint.completed) {
                progress(TagsProgress(type, checkpoint))
            }
        }
        return TagsProgress(type, checkpoint).also(progress)
    }

    /** Whether [type]'s log still holds a stream that records no tags. */
    suspend fun untagged(type: AggregateType): Boolean =
        (logOf(type.context) as? TagsBackfill)?.untagged(type, null, 1)?.isNotEmpty() == true

    /** Replays [ref] and records the tags of each of its streams that records none; how many it recorded. */
    private suspend fun backfill(
        kernel: Kernel,
        model: AggregateModel<Any>,
        log: EventLog,
        backfill: TagsBackfill,
        ref: AggregateRef,
    ): Int {
        var state = CachedState(model.initialState(ref), 0)
        val tags = buildMap {
            log.read(ref).toList().forEach { stream ->
                state = kernel.evolved(model, state, stream) ?: throw MigrationException(
                    "$ref skips a version before ${stream.version} in the V10 log: its tags cannot be derived.",
                )
                if (stream.tags == null) put(stream.version, state.tags)
            }
        }
        return backfill.recordTags(ref, tags)
    }
}
