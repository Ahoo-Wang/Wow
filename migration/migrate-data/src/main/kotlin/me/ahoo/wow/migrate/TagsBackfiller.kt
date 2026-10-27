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
import me.ahoo.wow.elasticsearch.events.ElasticsearchEventSink
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.TagsBackfill
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import kotlin.time.TimeSource

/**
 * Where the tags backfill of one V10 aggregate type stands (`backfill-tags`): [afterTenantId] and
 * [afterId] name the last aggregate whose streams were backfilled, in the log's key order (aggregate id, then
 * tenant); [completed] is set once none is left; [aggregates], [streams] and [millis] are those of every run since it
 * started: how many aggregates were replayed, how many streams got their tags, and how long it took.
 */
internal data class TagsCheckpoint(
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

/**
 * The documents of an aggregate type's events index the re-sink reads and overwrites ([TagsBackfiller.resink]): an
 * [ElasticsearchEventSink] ([of]), or a test's.
 */
internal interface UntaggedDocuments {
    /** The streams whose documents record no tags, in `(aggregateId, tenantId, version)` order after [after]. */
    suspend fun untagged(after: IndexedStream?, limit: Int): List<IndexedStream>

    /** Writes [records] over the documents of their streams. */
    suspend fun overwrite(records: List<StateEventRecord>)

    companion object {
        /** The documents [sink] writes. */
        fun of(sink: ElasticsearchEventSink): UntaggedDocuments = object : UntaggedDocuments {
            override suspend fun untagged(after: IndexedStream?, limit: Int): List<IndexedStream> =
                sink.untagged(after?.let { ElasticsearchEventSink.StreamKey(it.ref, it.version) }, limit)
                    .map { IndexedStream(it.ref, it.version) }

            override suspend fun overwrite(records: List<StateEventRecord>) = sink.overwrite(records)

            override fun toString(): String = sink.toString()
        }
    }
}

/** One indexed stream: its aggregate and version. */
internal data class IndexedStream(val ref: AggregateRef, val version: Int)

/** The tags backfill of one V10 aggregate type since it started; see [TagsCheckpoint]. */
internal data class TagsProgress(val type: AggregateType, val checkpoint: TagsCheckpoint) {
    fun render(): String {
        val perAggregate = if (checkpoint.aggregates == 0L) 0.0 else checkpoint.millis.toDouble() / checkpoint.aggregates
        return "$type tags${if (checkpoint.completed) " done" else ""}: ${checkpoint.aggregates} aggregates replayed, " +
            "${checkpoint.streams} streams tagged, ${checkpoint.millis} ms (${"%.2f".format(perAggregate)} ms per " +
            "aggregate)"
    }
}

/**
 * Backfills the tags of the streams a V10 log appended before format 1 learned them (-Q2c): each
 * aggregate with such a stream is replayed through its V10 model ([Kernel.evolved]), and every stream of it that
 * records no tags gets the tags the aggregate had after it, as the kernel records them at a commit.
 *
 * - **Only the missing.** A stream that records tags is never changed ([TagsBackfill.recordTags]), so a run again,
 *   or a run while the service appends, writes nothing twice.
 * - **Resumable.** Aggregates are walked in the log's key order, a page of [ImportOptions.pageSize] at a time,
 *   [ImportOptions.concurrency] at a time, and the checkpoint ([TagsCheckpoint]) moves after each page;
 *   [ImportOptions.restart] walks from the first again.
 * - **Elasticsearch after.** An events index written before the tags holds documents without them: [resink]
 *   overwrites them from the log once it is backfilled ([untagged] says none is left).
 */
internal class TagsBackfiller(
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

    /**
     * Re-sinks the documents of [type]'s events index that were indexed before the index learned the tags:
     * each such stream read from the log, its tags now recorded there, and written over its document
     * ([UntaggedDocuments.overwrite]), the only documents this writes. Run after [backfill]: while the log still
     * holds a stream without tags it refuses, unless [force], which leaves the documents of those streams as they are.
     * An overwritten document is no longer listed, so a run again, or after a stop, goes on where it was and
     * overwrites nothing twice. Returns how many it overwrote.
     */
    suspend fun resink(type: AggregateType, documents: UntaggedDocuments, force: Boolean = false): Long {
        if (!force && untagged(type)) {
            throw MigrationException(
                "The V10 log of $type still holds streams without tags: backfill it first, or re-sink with --force.",
            )
        }
        val log = logOf(type.context)
        var after: IndexedStream? = null
        var overwritten = 0L
        while (true) {
            val page = documents.untagged(after, options.pageSize)
            if (page.isEmpty()) break
            val records = page.mapNotNull { key ->
                val stream = log.read(key.ref, key.version, key.version).toList().singleOrNull()
                    ?: throw MigrationException("${key.ref} v${key.version} is indexed but not in the V10 log.")
                stream.takeIf { it.tags != null }?.let(::recordOf)
            }
            documents.overwrite(records)
            overwritten += records.size
            after = page.last()
        }
        return overwritten
    }

    /** [stream] as the sink subscription hands it to a Sink: its events as committed. */
    private fun recordOf(stream: EventStreamRecord): StateEventRecord = StateEventRecord.of(
        stream.ref,
        stream.version,
        commandId = stream.commandId,
        requestId = stream.requestId,
        commandType = stream.commandType,
        commitTime = stream.commitTime,
        events = stream.events.map { StateEventPayload.of(it.name, it.revision, WowJson.mapper.readTree(it.payload)) },
        scope = stream.scope,
        operator = stream.operator,
        tags = checkNotNull(stream.tags),
    )

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
