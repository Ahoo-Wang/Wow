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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import java.util.concurrent.atomic.AtomicLong

/**
 * How [EventStreamImporter] runs.
 *
 * - [pageSize] instances are imported between two checkpoints, [concurrency] at a time; [streamBatch] streams of an
 *   instance are read from the 9.x store at once. The tail reads [pageSize] streams at once.
 * - [dryRun] reads and converts every stream but appends none and saves no checkpoint: the mapping check's dry run
 *   extended to every payload.
 * - [restart] walks every instance again, ignoring where the last full import stopped: the final complete pass once
 *   9.x has stopped writing (design 07 §3 step 3).
 * - [lookbackMillis] is how far before the last commit time it read the tail scans again: 9.x nodes' clocks differ and
 *   a stream becomes visible after its `createTime`, so a stream committed earlier may appear later.
 * - [maxUnknownRetries] is how often an append whose outcome is unknown is tried again.
 */
data class ImportOptions(
    val pageSize: Int = 500,
    val streamBatch: Int = 200,
    val concurrency: Int = 8,
    val dryRun: Boolean = false,
    val restart: Boolean = false,
    val lookbackMillis: Long = 300_000,
    val maxUnknownRetries: Int = 3,
) {
    init {
        require(pageSize >= 1) { "A page holds at least one instance." }
        require(streamBatch >= 1) { "A batch holds at least one stream." }
        require(concurrency >= 1) { "At least one instance at a time." }
        require(lookbackMillis >= 0) { "The look-back window is never negative." }
        require(maxUnknownRetries >= 0) { "The retries are never negative." }
    }
}

/**
 * The throughput of an import or a tail pass of [aggregate] so far (design 07 §6: monitoring during the migration):
 * the [instances] walked, the streams [imported] and [skipped] (already in the log, from an earlier run), the
 * [events] imported and the request ids [rewritten] because another instance of the type holds them.
 */
data class ImportProgress(
    val aggregate: NineXAggregate,
    val instances: Long,
    val imported: Long,
    val skipped: Long,
    val events: Long,
    val rewritten: Long,
    val elapsedMillis: Long,
    val done: Boolean,
) {
    /** The streams handled per second. */
    val streamsPerSecond: Double
        get() = (imported + skipped) * MILLIS_PER_SECOND / elapsedMillis.coerceAtLeast(1)

    fun render(): String =
        "$aggregate${if (done) " done" else ""}: $instances instances, $imported streams imported, $skipped skipped, " +
            "$events events, $rewritten request ids rewritten, ${"%.1f".format(streamsPerSecond)} streams/s"

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

/**
 * Imports the streams of 9.x aggregates into the V10 log (design 07 §3 steps 2 and 3), with the [plan] whose check
 * passed; [logOf] gives the log of a V10 context.
 *
 * **Full import** ([importAll]): every instance of the aggregate in id order, each instance's streams in version
 * order from version 1; a checkpoint after every page of instances, so a stopped run resumes where it stopped.
 *
 * **Tail** ([tailOnce]): the streams committed since the full import started, by commit time, again and again until
 * cut-over; each new stream's instance is brought up to date from that version on.
 *
 * **Idempotence.** Every append can meet a stream an earlier run imported; the log's unique keys tell it (design 07 §3
 * step 3), and nothing is written twice:
 * - the request is already committed for the same instance and version: the stream is there, skipped;
 * - the request is committed for another instance of the type: 9.x keys requests by instance, V10 by tenant within the
 *   type (decision D162), so the stream is imported under the request id `<requestId>#<9.x stream id>`, which is the
 *   same on every run;
 * - the version is taken: the log's stream at its version must be the 9.x stream there, by command id, and the
 *   instance resumes after it; a different stream means V10 has been written beyond 9.x, and the import stops;
 * - the log is behind (the tail met a stream whose predecessors it has not read yet): the missing streams are read
 *   from 9.x first.
 */
class EventStreamImporter(
    private val source: NineXEventSource,
    private val plan: MigrationPlan,
    private val logOf: (String) -> EventLog,
    private val checkpoints: CheckpointRepository,
    private val options: ImportOptions = ImportOptions(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val progress: (ImportProgress) -> Unit = {},
) {
    private val converter = StreamConverter(plan)

    /** Imports every instance of [aggregate] not yet imported, and marks its full import complete. */
    suspend fun importAll(aggregate: NineXAggregate): ImportProgress {
        val aggregatePlan = plan.of(aggregate)
        val stats = Stats(aggregate, clock())
        var checkpoint = checkpoints.load().of(aggregate)
            ?.let { if (options.restart) it.copy(after = null, completed = false) else it }
            ?: AggregateCheckpoint(startedAt = clock())
        if (checkpoint.completed) {
            return stats.report(done = true).also(progress)
        }
        save(aggregate, checkpoint)
        val permits = Semaphore(options.concurrency)
        while (true) {
            val page = blocking { source.aggregateIds(aggregate, checkpoint.after, options.pageSize) }
            if (page.isEmpty()) {
                break
            }
            coroutineScope {
                page.map { id ->
                    async { permits.withPermit { importInstance(aggregatePlan, id, 1, stats) } }
                }.awaitAll()
            }
            stats.instances.addAndGet(page.size.toLong())
            checkpoint = checkpoint.copy(after = page.last())
            save(aggregate, checkpoint)
            progress(stats.report(done = false))
        }
        save(aggregate, checkpoint.copy(completed = true))
        return stats.report(done = true).also(progress)
    }

    /**
     * One pass of the tail of [aggregate]: imports what 9.x committed since the last pass, less the look-back window,
     * and moves the tail's checkpoint to the latest commit time it read. Its full import must be complete.
     */
    suspend fun tailOnce(aggregate: NineXAggregate): ImportProgress {
        val aggregatePlan = plan.of(aggregate)
        val checkpoint = checkpoints.load().of(aggregate)
        if (checkpoint == null || !checkpoint.completed) {
            throw MigrationException("The full import of $aggregate has not completed: run the import before the tail.")
        }
        val stats = Stats(aggregate, clock())
        val reached = HashMap<NineXAggregateId, Int>()
        val from = (checkpoint.tailedTo ?: checkpoint.startedAt) - options.lookbackMillis
        var latest = checkpoint.tailedTo ?: checkpoint.startedAt
        var after: NineXPosition? = null
        while (true) {
            val page = blocking { source.committedSince(aggregate, from, after, options.pageSize) }
            if (page.isEmpty()) {
                break
            }
            page.forEach { stream ->
                if ((reached[stream.aggregateId] ?: 0) < stream.version) {
                    stats.instances.incrementAndGet()
                    reached[stream.aggregateId] = importInstance(aggregatePlan, stream.aggregateId, stream.version, stats)
                }
                latest = maxOf(latest, stream.createTime)
            }
            after = page.last().let { NineXPosition(it.createTime, it.id) }
        }
        save(aggregate, checkpoint.copy(tailedTo = latest))
        return stats.report(done = true).also(progress)
    }

    /** Imports [id]'s streams from [fromVersion] on, and returns the last version the log then holds of it. */
    private suspend fun importInstance(plan: AggregatePlan, id: NineXAggregateId, fromVersion: Int, stats: Stats): Int {
        var next = fromVersion
        while (true) {
            val batch = blocking { source.streams(plan.source, id, next, options.streamBatch) }
            if (batch.isEmpty()) {
                return next - 1
            }
            var resumeAt: Int? = null
            for (stream in batch) {
                if (stream.version != next) {
                    throw MigrationException(
                        "9.x stream ${stream.id} of ${plan.source}/$id is version ${stream.version} where $next was " +
                            "expected: the 9.x history has a gap.",
                    )
                }
                val record = converter.convert(stream)
                if (options.dryRun) {
                    stats.imported(record)
                    next++
                    continue
                }
                resumeAt = append(plan, record, stream, stats)
                if (resumeAt != null) {
                    break
                }
                next++
            }
            resumeAt?.let { next = it }
        }
    }

    /**
     * Appends [record], converted from [stream]; returns null when the log now holds it (appended or already there),
     * or the version the instance's import resumes from.
     */
    @Suppress("CyclomaticComplexMethod") // One branch per append outcome, each settled where it is read.
    private suspend fun append(
        plan: AggregatePlan,
        record: EventStreamRecord,
        stream: NineXStream,
        stats: Stats,
    ): Int? {
        val log = logOf(record.ref.type.context)
        var attempt = record
        var unknowns = 0
        while (true) {
            when (val outcome = log.append(attempt)) {
                is AppendOutcome.Committed -> {
                    stats.imported(attempt)
                    if (attempt !== record) {
                        stats.rewritten.incrementAndGet()
                    }
                    return null
                }

                is AppendOutcome.AlreadyCommitted -> {
                    val existing = outcome.existing
                    if (existing.ref == attempt.ref && existing.version == attempt.version) {
                        stats.skipped.incrementAndGet()
                        return null
                    }
                    if (attempt !== record) {
                        throw MigrationException(
                            "9.x stream ${stream.id}: the request id ${attempt.requestId} is committed for " +
                                "${existing.ref} v${existing.version}.",
                        )
                    }
                    attempt = record.copy(requestId = "${record.requestId}#${stream.id}")
                }

                is AppendOutcome.Conflict -> {
                    val current = outcome.currentVersion
                    when {
                        current >= attempt.version -> return verified(plan, stream, log, attempt, current, stats)
                        current < attempt.version - 1 -> return current + 1
                        else -> unknowns = retry(unknowns, stream, IllegalStateException("conflict at $current"))
                    }
                }

                is AppendOutcome.Unknown -> unknowns = retry(unknowns, stream, outcome.cause)
                is AppendOutcome.Rejected -> throw MigrationException(
                    "9.x stream ${stream.id}: the log refuses ${attempt.ref} v${attempt.version}: " +
                        "${outcome.cause.message}",
                ).apply { initCause(outcome.cause) }
            }
        }
    }

    /**
     * The log already holds [record]'s instance up to [current]: checks that its stream at [current] is the 9.x one,
     * and returns the version after it.
     */
    private suspend fun verified(
        plan: AggregatePlan,
        stream: NineXStream,
        log: EventLog,
        record: EventStreamRecord,
        current: Int,
        stats: Stats,
    ): Int {
        val inLog = log.read(record.ref, current, current).firstOrNull()
        val inNineX = blocking { source.streams(plan.source, stream.aggregateId, current, 1) }
            .firstOrNull { it.version == current }
        if (inNineX == null || inLog?.commandId != inNineX.commandId) {
            throw MigrationException(
                "${record.ref} v$current in the V10 log is not the 9.x stream there (${inNineX?.id ?: "none"}): V10 " +
                    "has been written to since the import began, so the import stops (design 07 §3; decision D32).",
            )
        }
        stats.skipped.addAndGet((current - record.version + 1).toLong())
        return current + 1
    }

    private fun retry(unknowns: Int, stream: NineXStream, cause: Throwable): Int {
        if (unknowns >= options.maxUnknownRetries) {
            throw MigrationException("9.x stream ${stream.id}: the append's outcome stayed unknown: $cause")
        }
        return unknowns + 1
    }

    private fun save(aggregate: NineXAggregate, checkpoint: AggregateCheckpoint) {
        if (!options.dryRun) {
            checkpoints.save(checkpoints.load().with(aggregate, checkpoint))
        }
    }

    private suspend fun <T> blocking(read: () -> T): T = withContext(Dispatchers.IO) { read() }

    private inner class Stats(val aggregate: NineXAggregate, val startedAt: Long) {
        val instances = AtomicLong()
        val imported = AtomicLong()
        val skipped = AtomicLong()
        val events = AtomicLong()
        val rewritten = AtomicLong()

        fun imported(record: EventStreamRecord) {
            imported.incrementAndGet()
            events.addAndGet(record.events.size.toLong())
        }

        fun report(done: Boolean): ImportProgress = ImportProgress(
            aggregate = aggregate,
            instances = instances.get(),
            imported = imported.get(),
            skipped = skipped.get(),
            events = events.get(),
            rewritten = rewritten.get(),
            elapsedMillis = clock() - startedAt,
            done = done,
        )
    }
}
