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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import java.util.concurrent.atomic.AtomicLong

/**
 * Where an import's workers spent their time, each phase summed over the instances imported at once
 * ([ImportOptions.concurrency]), so that one can exceed the import's own time: reading the 9.x store ([readMillis]:
 * the pages of instances and their streams), converting the streams ([convertMillis]), appending them to the V10 log
 * ([appendMillis], with the checks of what the log already holds and the retries), and saving the checkpoints
 * ([checkpointMillis]).
 */
internal data class ImportPhases(
    val readMillis: Long = 0,
    val convertMillis: Long = 0,
    val appendMillis: Long = 0,
    val checkpointMillis: Long = 0,
) {
    fun render(): String =
        "worker time: read 9.x ${seconds(readMillis)}, convert ${seconds(convertMillis)}, " +
            "append ${seconds(appendMillis)}, checkpoints ${seconds(checkpointMillis)}"
}

/**
 * The throughput of an import or a tail pass of [aggregate] so far (monitoring during the migration):
 * the [instances] walked, the streams [imported] and [skipped] (already in the log, from an earlier run), the
 * [events] imported, the request ids [rewritten] because another instance of the type holds them, and the instances
 * [excluded] by the map ([Exclusion.stream]), whose streams the full import passes over. Once [done], it also gives its
 * time and where the time went ([phases]).
 */
internal data class ImportProgress(
    val aggregate: NineXAggregate,
    val instances: Long,
    val imported: Long,
    val skipped: Long,
    val events: Long,
    val rewritten: Long,
    val elapsedMillis: Long,
    val done: Boolean,
    val excluded: Long = 0,
    val phases: ImportPhases = ImportPhases(),
) {
    /** The streams handled per second. */
    val streamsPerSecond: Double
        get() = (imported + skipped) * MILLIS_PER_SECOND / elapsedMillis.coerceAtLeast(1)

    fun render(): String =
        "$aggregate${if (done) " done" else ""}: $instances instances, $imported streams imported, $skipped skipped, " +
            "$events events, $rewritten request ids rewritten, ${if (excluded > 0) "$excluded excluded, " else ""}" +
            "${"%.1f".format(streamsPerSecond)} streams/s" +
            if (done) " in ${seconds(elapsedMillis)}; ${phases.render()}" else ""

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

/** [millis] in seconds, to the tenth. */
private fun seconds(millis: Long): String = "%.1f s".format(millis / 1000.0)

/**
 * Imports the streams of 9.x aggregates into the V10 log (steps 2 and 3), with the [plan] whose check
 * passed; [logOf] gives the log of a V10 context.
 *
 * **Full import** ([importAll]): every instance of the aggregate in id order, each instance's streams in version
 * order from version 1; a checkpoint after every page of instances, so a stopped run resumes where it stopped.
 *
 * **Tail** ([tailOnce]): the streams committed since the full import started, by commit time, again and again until
 * cut-over, each pass up to the latest stream committed when it starts; each new stream's instance is brought up to
 * date from that version on.
 *
 * Both pass over the instances the map excludes ([MigrationPlan.excludes]).
 *
 * **Idempotence.** Every append can meet a stream an earlier run imported; the log's unique keys tell it
 * (step 3), and nothing is written twice:
 * - the request is already committed for the same instance and version: the stream is there, skipped;
 * - the request is committed for another instance of the type: 9.x keys requests by instance, V10 by tenant within the
 *   type, so the stream is imported under the request id `<requestId>#<9.x stream id>`, which is the
 *   same on every run;
 * - the version is taken: the log's stream at its version must be the 9.x stream there, by command id, and the
 *   instance resumes after it; a different stream means V10 has been written beyond 9.x, and the import stops;
 * - the log is behind (the tail met a stream whose predecessors it has not read yet): the missing streams are read
 *   from 9.x first.
 */
internal class EventStreamImporter(
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
        val loaded = checkpoints.load().of(aggregate)
        if (loaded?.completed == true && !options.restart) {
            return stats.report(done = true).also(progress)
        }
        // An earlier version's single position resumes as one range from there, which holds it from then on.
        val ranges = loaded?.ranges?.takeUnless { options.restart }
            ?: if (loaded == null || options.restart) {
                rangesOf(aggregate, stats)
            } else {
                listOf(RangeCheckpoint(after = loaded.after))
            }
        val checkpoint = (loaded ?: AggregateCheckpoint(startedAt = clock()))
            .copy(after = null, completed = false, ranges = ranges)
        save(aggregate, checkpoint)
        val permits = Semaphore(options.concurrency)
        coroutineScope {
            ranges.forEachIndexed { index, range ->
                if (!range.completed) {
                    launch { importRange(aggregate, aggregatePlan, index, range, permits, stats) }
                }
            }
        }
        stats.timed(stats.checkpoint) { update(aggregate) { it.copy(completed = true) } }
        return stats.report(done = true).also(progress)
    }

    /** The ranges [aggregate]'s walk is split into, from ids sampled from the 9.x store, each where it starts. */
    private suspend fun rangesOf(aggregate: NineXAggregate, stats: Stats): List<RangeCheckpoint> {
        val boundaries = stats.timed(stats.read) {
            blocking { source.idBoundaries(aggregate, options.readParallelism) }
        }
        return IdRange.split(boundaries).map { RangeCheckpoint(it.from, it.until) }
    }

    /**
     * Walks the range [index] of [aggregate], from where [range] stands: its pages read one ahead, at most
     * [PAGES_IN_FLIGHT] of them appended at once, the range's checkpoint moving after each once it and the pages before
     * it are imported. [permits] bound the groups in flight with every other range's.
     */
    private suspend fun importRange(
        aggregate: NineXAggregate,
        aggregatePlan: AggregatePlan,
        index: Int,
        range: RangeCheckpoint,
        permits: Semaphore,
        stats: Stats,
    ) {
        var at = range
        coroutineScope {
            val pages = readAhead(aggregate, range.range, range.after, stats)
            val inFlight = ArrayDeque<PageImport>()
            for (page in pages) {
                val imported = page.filterNot { plan.excludes(aggregate, it.id.id) }
                inFlight += importPage(aggregatePlan, page, imported, permits, stats)
                while (inFlight.isNotEmpty() && (inFlight.size > PAGES_IN_FLIGHT || inFlight.first().done)) {
                    at = settled(aggregate, index, inFlight.removeFirst(), at, stats)
                }
            }
            while (inFlight.isNotEmpty()) {
                at = settled(aggregate, index, inFlight.removeFirst(), at, stats)
            }
        }
        stats.timed(stats.checkpoint) { update(aggregate) { it.withRange(index, at.copy(completed = true)) } }
    }

    /**
     * The full import's pages of [aggregate] after [after], read one ahead of the pages being imported (no page
     * barrier): the next page's scan runs while this page's streams are appended.
     */
    private fun CoroutineScope.readAhead(
        aggregate: NineXAggregate,
        range: IdRange,
        after: NineXAggregateId?,
        stats: Stats,
    ): ReceiveChannel<List<NineXInstance>> {
        val pages = Channel<List<NineXInstance>>(capacity = 1)
        launch {
            try {
                var last = after
                while (true) {
                    val page = stats.timed(stats.read) {
                        blocking {
                            if (range == IdRange.ALL) {
                                source.instances(aggregate, last, options.pageSize, options.pageStreams)
                            } else {
                                source.instances(aggregate, range, last, options.pageSize, options.pageStreams)
                            }
                        }
                    }
                    if (page.isEmpty()) {
                        break
                    }
                    pages.send(page)
                    last = page.last().id
                }
            } finally {
                pages.close()
            }
        }
        return pages
    }

    /** One page of the full import, its instances' imports started, [ImportOptions.concurrency] at a time in all. */
    private class PageImport(val page: List<NineXInstance>, val imported: Int, val jobs: List<Deferred<Unit>>) {
        val done: Boolean get() = jobs.all { it.isCompleted }
    }

    /**
     * Starts the import of [page]'s instances the map does not exclude, [imported]: their streams in groups of whole
     * instances ([importGroup]), or one instance after another in a dry run, which appends nothing; [permits] bound
     * them with the other pages' in flight.
     */
    private fun CoroutineScope.importPage(
        plan: AggregatePlan,
        page: List<NineXInstance>,
        imported: List<NineXInstance>,
        permits: Semaphore,
        stats: Stats,
    ): PageImport {
        val jobs = if (options.dryRun) {
            imported.map { instance -> async { permits.withPermit { importInstance(plan, instance, stats) } } }
        } else {
            groupsOf(imported).map { group -> async { permits.withPermit { importGroup(plan, group, stats) } } }
        }
        return PageImport(page, imported.size, jobs)
    }

    /**
     * Waits for [import]'s page, the oldest in flight, and moves the checkpoint after it: every instance up to its last
     * is imported, those of the pages before it too, whatever the pages after it have done.
     */
    private suspend fun settled(
        aggregate: NineXAggregate,
        index: Int,
        import: PageImport,
        range: RangeCheckpoint,
        stats: Stats,
    ): RangeCheckpoint {
        import.jobs.awaitAll()
        stats.instances.addAndGet(import.imported.toLong())
        stats.excluded.addAndGet((import.page.size - import.imported).toLong())
        val moved = range.copy(after = import.page.last().id)
        stats.timed(stats.checkpoint) { update(aggregate) { it.withRange(index, moved) } }
        progress(stats.report(done = false))
        return moved
    }

    /**
     * One pass of the tail of [aggregate]: imports what 9.x committed since the last pass, less the look-back window,
     * up to the latest stream committed when the pass starts, and moves the tail's checkpoint to the latest commit time
     * it read. Its full import must be complete.
     *
     * The pass ends there because, read to the end, a pass would not end while 9.x writes steadily, and its cursor only
     * goes forward: a stream that becomes visible after the cursor went past its commit time is left to the next pass,
     * which starts a look-back before this pass's latest commit time, so a pass longer than the look-back would lose
     * it. Bounded, each pass starts no later than a look-back before the latest stream visible when the last one
     * started, so a stream that becomes visible within the look-back of its commit time is read by a later pass; the
     * streams a pass reads again are skipped with one read of the log ([tailPage]).
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
        val until = stats.timed(stats.read) { blocking { source.lastCommitted(aggregate) } }
        var after: NineXPosition? = null
        while (until != null) {
            val page = stats.timed(stats.read) {
                blocking { source.committedSince(aggregate, from, after, options.pageSize, until) }
            }
            if (page.isEmpty()) {
                break
            }
            tailPage(aggregatePlan, page.filterNot { plan.excludes(aggregate, it.aggregateId.id) }, reached, stats)
            // A page is in commit-time order: its last stream is its latest.
            val last = page.last()
            latest = maxOf(latest, last.createTime)
            after = NineXPosition(last.createTime, last.id)
        }
        stats.timed(stats.checkpoint) { save(aggregate, checkpoint.copy(tailedTo = latest, tailedAt = clock())) }
        return stats.report(done = true).also(progress)
    }

    /**
     * The streams of one page of the tail, by instance, [ImportOptions.concurrency] instances at a time: an instance
     * whose latest stream of the page the log already holds, the same command at the same version, is skipped with one
     * read of the log; any other is brought up to date from its earliest stream of the page on ([importInstance]),
     * which also settles a stream the log holds otherwise. Most of what a final pass reads again from its look-back
     * window is skipped this way, so it costs the streams 9.x committed since the tail last read.
     */
    private suspend fun tailPage(
        plan: AggregatePlan,
        page: List<NineXStream>,
        reached: MutableMap<NineXAggregateId, Int>,
        stats: Stats,
    ) {
        val behind = page.filter { (reached[it.aggregateId] ?: 0) < it.version }.groupBy { it.aggregateId }
        val permits = Semaphore(options.concurrency)
        val done = coroutineScope {
            behind.map { (id, streams) ->
                async {
                    permits.withPermit {
                        stats.instances.incrementAndGet()
                        id to tailInstance(plan, id, streams, stats)
                    }
                }
            }.awaitAll()
        }
        done.forEach { (id, version) -> reached[id] = maxOf(reached[id] ?: 0, version) }
    }

    /** Brings [id] up to [streams]' latest version; returns the version the log then holds of it. */
    private suspend fun tailInstance(
        plan: AggregatePlan,
        id: NineXAggregateId,
        streams: List<NineXStream>,
        stats: Stats
    ): Int {
        val last = streams.maxBy { it.version }
        val held = logOf(plan.target.context).read(plan.target.ref(id.tenantId, id.id), last.version, last.version)
            .firstOrNull()
        if (held?.commandId == last.commandId) {
            stats.skipped.addAndGet(streams.size.toLong())
            return last.version
        }
        return importInstance(plan, id, streams.minOf { it.version }, stats)
    }

    /**
     * Why the final pass of [aggregate] cannot be its tail (step 3): its full import has not completed,
     * the tail never ran, or its last pass ended more than [maxTailAgeMillis] ago, so it did not follow 9.x
     * continuously; null when it can. A final pass that cannot be the tail is `import --restart`.
     */
    fun finalProblem(aggregate: NineXAggregate, maxTailAgeMillis: Long): String? {
        val checkpoint = checkpoints.load().of(aggregate)
        val tailedAt = checkpoint?.tailedAt
        return when {
            checkpoint == null || !checkpoint.completed -> "the full import of $aggregate has not completed"
            tailedAt == null -> "the tail of $aggregate never ran"
            clock() - tailedAt > maxTailAgeMillis ->
                "the tail of $aggregate last ended ${clock() - tailedAt} ms ago, more than $maxTailAgeMillis ms: it " +
                    "did not follow 9.x continuously"

            else -> null
        }
    }

    /**
     * [instances] in groups of whole instances, about [ImportOptions.concurrency] of them, each of at most
     * [MAX_GROUP_STREAMS] streams but for an instance that holds more alone.
     */
    private fun groupsOf(instances: List<NineXInstance>): List<List<NineXInstance>> {
        val streams = instances.sumOf { it.streams.size }
        val size = ((streams + options.concurrency - 1) / options.concurrency).coerceIn(1, MAX_GROUP_STREAMS)
        val groups = mutableListOf<List<NineXInstance>>()
        var group = mutableListOf<NineXInstance>()
        var held = 0
        for (instance in instances) {
            if (group.isNotEmpty() && held + instance.streams.size > size) {
                groups += group
                group = mutableListOf()
                held = 0
            }
            group += instance
            held += instance.streams.size
        }
        if (group.isNotEmpty()) {
            groups += group
        }
        return groups
    }

    /**
     * Imports [instances] as their page read them, with one call of the log for the streams read (group commit,
     * [EventLog.appendAll]): each instance's streams the log committed are imported; from its first stream it did not
     * commit, which the log answers as [EventLog.append] would, the instance goes on as [importInstance] does, one
     * stream at a time, so that stream's outcome is settled as it always was (already there, a request id another
     * instance holds, a version taken, an unknown outcome tried again); then the rest of an instance read in part.
     */
    private suspend fun importGroup(plan: AggregatePlan, instances: List<NineXInstance>, stats: Stats) {
        val records = instances.map { instance ->
            instance.streams.mapIndexed { index, stream ->
                if (stream.version != index + 1) {
                    throw MigrationException(
                        "9.x stream ${stream.id} of ${plan.source}/${instance.id} is version ${stream.version} " +
                            "where ${index + 1} was expected: the 9.x history has a gap.",
                    )
                }
                stats.timed(stats.convert) { converter.convert(stream) }
            }
        }
        val outcomes = stats.timed(stats.append) {
            logOf(plan.target.context).appendAll(records.flatten().map { it to null })
        }
        var at = 0
        instances.forEachIndexed { index, instance ->
            val converted = records[index]
            val committed = outcomes.subList(at, at + converted.size).takeWhile { it is AppendOutcome.Committed }.size
            at += converted.size
            converted.take(committed).forEach(stats::imported)
            val next = if (committed == converted.size) {
                committed + 1
            } else {
                importBatch(plan, instance.id, committed + 1, instance.streams.drop(committed), stats)
            }
            if (!instance.complete || next != instance.streams.size + 1) {
                importInstance(plan, instance.id, next, stats)
            }
        }
    }

    /**
     * Imports [instance] as its page read it: the streams read, then the rest from 9.x when the page read it in part,
     * or when the log made the import go on from another version.
     */
    private suspend fun importInstance(plan: AggregatePlan, instance: NineXInstance, stats: Stats) {
        val next = importBatch(plan, instance.id, 1, instance.streams, stats)
        if (!instance.complete || next != instance.streams.size + 1) {
            importInstance(plan, instance.id, next, stats)
        }
    }

    /** Imports [id]'s streams from [fromVersion] on, and returns the last version the log then holds of it. */
    private suspend fun importInstance(plan: AggregatePlan, id: NineXAggregateId, fromVersion: Int, stats: Stats): Int {
        var next = fromVersion
        while (true) {
            val batch = stats.timed(stats.read) {
                blocking { source.streams(plan.source, id, next, options.streamBatch) }
            }
            if (batch.isEmpty()) {
                return next - 1
            }
            next = importBatch(plan, id, next, batch, stats)
        }
    }

    /**
     * Imports [batch], [id]'s streams from [fromVersion] on in version order, and returns the version its import goes
     * on from: the one after the batch, or the one the log made it resume from.
     */
    private suspend fun importBatch(
        plan: AggregatePlan,
        id: NineXAggregateId,
        fromVersion: Int,
        batch: List<NineXStream>,
        stats: Stats,
    ): Int {
        var next = fromVersion
        for (stream in batch) {
            if (stream.version != next) {
                throw MigrationException(
                    "9.x stream ${stream.id} of ${plan.source}/$id is version ${stream.version} where $next was " +
                        "expected: the 9.x history has a gap.",
                )
            }
            val record = stats.timed(stats.convert) { converter.convert(stream) }
            if (options.dryRun) {
                stats.imported(record)
                next++
                continue
            }
            stats.timed(stats.append) { append(plan, record, stream, stats) }?.let { return it }
            next++
        }
        return next
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
                    attempt = record.copyWith(requestId = "${record.requestId}#${stream.id}")
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
                    "has been written to since the import began, so the import stops.",
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
            checkpoints.update { it.with(aggregate, checkpoint) }
        }
    }

    /**
     * Saves [change] of [aggregate]'s checkpoint as it is kept, in one step: each range moves only its own position,
     * whatever the others have saved since.
     */
    private fun update(aggregate: NineXAggregate, change: (AggregateCheckpoint) -> AggregateCheckpoint) {
        if (!options.dryRun) {
            checkpoints.update { saved -> saved.with(aggregate, change(saved.of(aggregate)!!)) }
        }
    }

    private inner class Stats(val aggregate: NineXAggregate, val startedAt: Long) {
        val instances = AtomicLong()
        val imported = AtomicLong()
        val skipped = AtomicLong()
        val events = AtomicLong()
        val rewritten = AtomicLong()
        val excluded = AtomicLong()
        val read = AtomicLong()
        val convert = AtomicLong()
        val append = AtomicLong()
        val checkpoint = AtomicLong()

        /**
         * Runs [block], adding its time to [phase]. Inline, so the block suspends or blocks in its caller's context,
         * as each phase's does, and no lambda is allocated per stream.
         */
        inline fun <T> timed(phase: AtomicLong, block: () -> T): T {
            val started = System.nanoTime()
            try {
                return block()
            } finally {
                phase.addAndGet(System.nanoTime() - started)
            }
        }

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
            excluded = excluded.get(),
            phases = ImportPhases(
                readMillis = read.get() / NANOS_PER_MILLI,
                convertMillis = convert.get() / NANOS_PER_MILLI,
                appendMillis = append.get() / NANOS_PER_MILLI,
                checkpointMillis = checkpoint.get() / NANOS_PER_MILLI,
            ),
        )
    }
}

private const val NANOS_PER_MILLI = 1_000_000L

/** The streams of one group commit at most: as many as one PostgreSQL statement of the log takes. */
private const val MAX_GROUP_STREAMS = 1000

/**
 * The full import's pages being appended at once, besides the one read ahead: a page's instances wait for no other
 * page's, so a slow group of one page does not hold the next one's back, and the checkpoint follows the oldest.
 */
private const val PAGES_IN_FLIGHT = 2
