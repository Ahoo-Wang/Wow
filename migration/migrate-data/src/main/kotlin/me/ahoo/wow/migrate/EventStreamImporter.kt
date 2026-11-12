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
import me.ahoo.wow.spi.log.EventLog

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
 * Both pass over the instances the map excludes ([MigrationPlan.excludes]), and append each instance's streams
 * through [InstanceAppends], which writes nothing twice.
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
    private val appends = InstanceAppends(source, logOf, StreamConverter(plan, clock), options)

    /** Imports every instance of [aggregate] not yet imported, and marks its full import complete. */
    suspend fun importAll(aggregate: NineXAggregate): ImportProgress {
        val aggregatePlan = plan.of(aggregate)
        val stats = ImportStats(aggregate, clock(), clock)
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
    private suspend fun rangesOf(aggregate: NineXAggregate, stats: ImportStats): List<RangeCheckpoint> {
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
        stats: ImportStats,
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
        stats: ImportStats,
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
        stats: ImportStats,
    ): PageImport {
        val jobs = if (options.dryRun) {
            imported.map { instance -> async { permits.withPermit { appends.importInstance(plan, instance, stats) } } }
        } else {
            appends.groupsOf(imported).map { group ->
                async { permits.withPermit { appends.importGroup(plan, group, stats) } }
            }
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
        stats: ImportStats,
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
        val stats = ImportStats(aggregate, clock(), clock)
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
        stats: ImportStats,
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
        stats: ImportStats
    ): Int {
        val last = streams.maxBy { it.version }
        val held = logOf(plan.target.context).read(plan.target.ref(id.tenantId, id.id), last.version, last.version)
            .firstOrNull()
        if (held?.commandId == last.commandId) {
            stats.skipped.addAndGet(streams.size.toLong())
            return last.version
        }
        return appends.importInstance(plan, id, streams.minOf { it.version }, stats)
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
}

/**
 * The full import's pages being appended at once, besides the one read ahead: a page's instances wait for no other
 * page's, so a slow group of one page does not hold the next one's back, and the checkpoint follows the oldest.
 */
private const val PAGES_IN_FLIGHT = 2
