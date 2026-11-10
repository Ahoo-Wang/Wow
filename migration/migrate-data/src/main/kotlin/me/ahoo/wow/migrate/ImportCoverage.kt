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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ahoo.wow.spi.log.EventLog
import java.time.Instant

/**
 * How [ImportCoverage] runs: [pageSize] streams read at once, [concurrency] instances looked up in the log at a time;
 * [lookbackMillis] is the tail's, read again before where the last check ended; [marginMillis] is how far before the
 * tail's checkpoint the check ends (the final pass's look-back); [restart] checks again from the full import's start.
 */
internal data class CoverageOptions(
    val pageSize: Int = 500,
    val concurrency: Int = 8,
    val lookbackMillis: Long = 300_000,
    val marginMillis: Long = 60_000,
    val restart: Boolean = false,
) {
    init {
        require(pageSize >= 1) { "A page holds at least one stream." }
        require(concurrency >= 1) { "At least one instance at a time." }
        require(lookbackMillis >= 0) { "The look-back window is never negative." }
        require(marginMillis >= 0) { "The margin is never negative." }
    }
}

/**
 * What the check of [aggregate]'s import found: the streams 9.x committed from [from] to [to] (commit times; null
 * when there was nothing to check, for the reason [unchecked]), of [instances] instances, of which [missing] have their
 * latest stream there missing from the log.
 */
internal data class CoverageReport(
    val aggregate: NineXAggregate,
    val from: Long? = null,
    val to: Long? = null,
    val streams: Long = 0,
    val instances: Long = 0,
    val missing: Long = 0,
    val unchecked: String? = null,
) {
    val passed: Boolean
        get() = missing == 0L

    fun render(): String = when {
        unchecked != null -> "$aggregate imported streams not checked: $unchecked"
        else ->
            "$aggregate imported streams checked: $streams streams 9.x committed from ${Instant.ofEpochMilli(from!!)} " +
                "to ${Instant.ofEpochMilli(to!!)}, of $instances instances; " +
                if (passed) "the log holds the latest of each" else "$missing of them are missing from the log"
    }
}

/**
 * The check `compare` makes of the import itself: the log holds every stream 9.x committed since the full import of
 * an aggregate started, by version and command.
 *
 * The state comparison ([StateComparison]) rebuilds V10 at each 9.x snapshot's version, so it cannot see a stream
 * missing after the instance's latest 9.x snapshot: with 9.x's `version_offset` snapshot strategy, before 9.x writes
 * the snapshot, or for an instance without one. This check reads the streams themselves, by commit time on the tail's
 * index, from the full import's start (less the tail's look-back) to [CoverageOptions.marginMillis] before the tail's
 * checkpoint: what the tail has gone past for good, which during the dual run excludes the streams it may still be
 * reading. For each instance of a page it looks up the latest stream in the log (one read, as the tail's duplicate
 * check), and reports each one missing as a `MISSING` line.
 *
 * Its position is kept in its own checkpoint ([CoverageCheckpoint]) when nothing is missing, so the
 * next check reads only what 9.x committed since, less the look-back: the cut-over's `compare` costs what the dual
 * run's writes cost since the last one. A check that finds a stream missing reads the same streams again next time.
 * An aggregate the tail never followed is not checked: its full import, the final pass then, walked every stream.
 */
internal class ImportCoverage(
    private val source: NineXEventSource,
    private val plan: MigrationPlan,
    private val logOf: (String) -> EventLog,
    private val checkpoints: CheckpointRepository,
    private val options: CoverageOptions = CoverageOptions(),
    private val report: (String) -> Unit = {},
) {
    /** Checks that the log holds the streams 9.x committed to [aggregate] since its full import started. */
    suspend fun check(aggregate: NineXAggregate): CoverageReport {
        val checkpoint = checkpoints.load().of(aggregate)
        val tailedTo = checkpoint?.tailedTo
            ?: return CoverageReport(aggregate, unchecked = "the tail never followed it").also { report(it.render()) }
        val checked = checkpoints.load().coverageOf(aggregate)?.checkedTo?.takeUnless { options.restart }
        val from = (checked ?: checkpoint.startedAt) - options.lookbackMillis
        val to = tailedTo - options.marginMillis
        if (to < from) {
            val unchecked = "the tail's checkpoint is within ${options.marginMillis} ms of where the check starts"
            return CoverageReport(aggregate, unchecked = unchecked).also { report(it.render()) }
        }
        val log = logOf(plan.of(aggregate).target.context)
        val instances = HashSet<NineXAggregateId>()
        var streams = 0L
        var missing = 0L
        var after: NineXPosition? = null
        while (true) {
            val read = blocking { source.committedSince(aggregate, from, after, options.pageSize) }
            val page = read.takeWhile { it.createTime <= to }
            streams += page.size
            val latest = page.filterNot { plan.excludes(aggregate, it.aggregateId.id) }.groupBy { it.aggregateId }
                .values.map { versions -> versions.maxBy { it.version } }
            instances += latest.map { it.aggregateId }
            missing += missingOf(aggregate, log, latest).onEach { report(it) }.size
            if (page.isEmpty() || page.size < read.size || read.size < options.pageSize) {
                break
            }
            after = page.last().let { NineXPosition(it.createTime, it.id) }
        }
        if (missing == 0L) {
            checkpoints.update { it.with(aggregate, CoverageCheckpoint(to)) }
        }
        return CoverageReport(
            aggregate,
            from,
            to,
            streams,
            instances.size.toLong(),
            missing
        ).also { report(it.render()) }
    }

    /** The `MISSING` line of each of [latest] the log does not hold, [ImportCoverage]'s concurrency at a time. */
    private suspend fun missingOf(aggregate: NineXAggregate, log: EventLog, latest: List<NineXStream>): List<String> {
        val target = plan.of(aggregate).target
        val permits = Semaphore(options.concurrency)
        return coroutineScope {
            latest.map { stream ->
                async {
                    permits.withPermit {
                        val ref = target.ref(stream.aggregateId.tenantId, stream.aggregateId.id)
                        val held = log.read(ref, stream.version, stream.version).firstOrNull()
                        when {
                            held?.commandId == stream.commandId -> null
                            held != null -> "another command, ${held.commandId}, at v${stream.version}"
                            else -> log.read(ref, 1, stream.version).lastOrNull()?.let { "v${it.version}" }
                                ?: "none of its versions"
                        }?.let { holds ->
                            "MISSING $aggregate ${stream.aggregateId} v${stream.version}: 9.x stream ${stream.id} " +
                                "(command ${stream.commandId}), committed at ${Instant.ofEpochMilli(stream.createTime)}, " +
                                "is not in the log, which holds $holds"
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}
