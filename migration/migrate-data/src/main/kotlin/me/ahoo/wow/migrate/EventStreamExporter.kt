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
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.LogEntry
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * How [EventStreamExporter] runs: [pageSize] log entries of a partition are read between two checkpoints,
 * [concurrency] partitions at a time; [dryRun] scans and writes nothing; [restart] exports from each partition's first
 * entry again (what 9.x already holds is skipped).
 */
internal data class ExportOptions(
    val pageSize: Int = 500,
    val concurrency: Int = 8,
    val dryRun: Boolean = false,
    val restart: Boolean = false,
) {
    init {
        require(pageSize >= 1) { "A page holds at least one entry." }
        require(concurrency >= 1) { "At least one partition at a time." }
    }
}

/**
 * What the scan of a V10 [context]'s log found, up to the [heads] of its partitions when it started: the [streams]
 * of each mapped aggregate type, how many of them 9.x does not hold yet ([missing]), and why some of those cannot go
 * back ([problems], each with the number of streams it stops, and the earliest commit time among them in
 * [firstRefused]; [conflicts], streams 9.x holds otherwise).
 */
internal data class ExportScan(
    val context: String,
    val heads: Map<Int, Long>,
    val streams: Map<AggregateType, Long>,
    val missing: Long,
    val problems: Map<String, Long>,
    val conflicts: List<String>,
    val firstRefused: Map<String, Long> = emptyMap(),
) {
    /** Whether every stream 9.x lacks can go back. */
    val passed: Boolean
        get() = problems.isEmpty() && conflicts.isEmpty()

    /**
     * When the rollback by export stopped being possible: the commit time of the first stream V10 wrote that 9.x
     * cannot read; null while every such stream can go back.
     */
    val closedAt: Long?
        get() = firstRefused.values.minOrNull()

    /** The rollback the scan allows, for the operator. */
    val verdict: String
        get() = when {
            closedAt != null ->
                "VERDICT $context: no rollback by export: the window closed at " +
                    "${Instant.ofEpochMilli(checkNotNull(closedAt))}, when V10 first committed a type 9.x cannot read"
            conflicts.isNotEmpty() -> "VERDICT $context: no rollback by export: 9.x was written after the cut-over"
            missing == 0L -> "VERDICT $context: roll back by switching back: 9.x holds every stream V10 holds"
            else -> "VERDICT $context: roll back by export: $missing streams go back to 9.x"
        }

    fun render(): String = buildString {
        append("$context: ${streams.values.sum()} streams up to the heads, $missing not in 9.x")
        streams.forEach { (type, count) -> append("\n  $type: $count streams") }
        problems.forEach { (problem, count) ->
            val first = firstRefused[problem]?.let { ", the first committed at ${Instant.ofEpochMilli(it)}" }.orEmpty()
            append("\n  REFUSED $problem ($count streams$first)")
        }
        conflicts.forEach { append("\n  CONFLICT $it") }
        append("\n").append(verdict)
    }
}

/** The export of a V10 [context] so far: the streams written to 9.x ([exported]) and those it already held. */
internal data class ExportProgress(
    val context: String,
    val exported: Long,
    val already: Long,
    val elapsedMillis: Long,
    val done: Boolean,
) {
    fun render(): String =
        "$context${if (done) " done" else ""}: $exported streams exported to 9.x, $already already there, " +
            "${"%.1f".format((exported + already) * MILLIS_PER_SECOND / elapsedMillis.coerceAtLeast(1))} streams/s"

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

/** The streams of a 9.x [aggregate] once the export is done: as many as V10 holds of its type, or not. */
internal data class ExportCount(
    val aggregate: NineXAggregate,
    val type: AggregateType,
    val v10: Long,
    val nineX: Long
) {
    val passed: Boolean
        get() = v10 == nineX

    fun render(): String =
        "${if (passed) "EQUAL" else "DIFFERS"} $aggregate <- $type: V10 $v10 streams, 9.x $nineX streams"
}

/**
 * Exports what V10 wrote after the cut-over back to the 9.x store, for a rollback after V10 has written:
 * every stream of a mapped aggregate type that 9.x does not hold, converted back by the map ([StreamReverter]).
 *
 * - **Scan** ([scan]): every partition of the context's log, up to the head each had when the scan started, is read
 *   and each stream looked up in 9.x by its instance and version: one 9.x holds is skipped; one it holds otherwise (a
 *   different command or tenant) is a conflict; one it lacks must go back, and a V10-only type in it (an aggregate no
 *   9.x aggregate maps to, an event at a revision or name the map does not reach) refuses the export. Nothing is
 *   written; the scan also counts each type's streams for [verify].
 * - **Export** ([export]): the partitions again, from each one's checkpoint up to the scan's heads, the streams 9.x
 *   lacks written to it a page at a time. It is idempotent: a stream 9.x already holds is skipped, so a stopped run
 *   resumes, and a run again writes nothing.
 * - **Verify** ([verify]): each 9.x aggregate holds as many streams as V10 holds of its type.
 *
 * V10 must have stopped writing: what it commits after the scan is not exported. Only events go back: a reservation
 * or a parked entry V10 made after the cut-over has no 9.x form here, and neither does a projection's view. 9.x then
 * reads each instance from its last snapshot on, so its snapshots, older than the exported streams, still hold.
 */
internal class EventStreamExporter(
    private val plan: MigrationPlan,
    private val logOf: (String) -> EventLog,
    private val target: NineXEventTarget,
    private val checkpoints: CheckpointRepository,
    private val options: ExportOptions = ExportOptions(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val progress: (ExportProgress) -> Unit = {},
) {
    private val reverter = StreamReverter(plan, target::revisionsOf)

    /** Scans [context]'s log up to its heads now: what the export would write, and what stops it. */
    suspend fun scan(context: String): ExportScan {
        val log = logOf(context)
        val heads = (0 until log.partitions).mapNotNull { partition ->
            log.head(
                partition
            )?.let { partition to it }
        }.toMap()
        val streams = ConcurrentHashMap<AggregateType, AtomicLong>()
        val missing = AtomicLong()
        val problems = ConcurrentHashMap<String, AtomicLong>()
        val firstRefused = ConcurrentHashMap<String, Long>()
        val conflicts = ConcurrentHashMap.newKeySet<String>()
        forEachPartition(heads) { partition, head ->
            walk(log, partition, null, head) { page ->
                val missingOf = missingOf(page) { conflicts += it }
                page.forEach { streams.computeIfAbsent(it.stream.ref.type) { AtomicLong() }.incrementAndGet() }
                missingOf.forEach { entry ->
                    missing.incrementAndGet()
                    reverter.problemsOf(entry.stream).forEach { problem ->
                        problems.computeIfAbsent(problem) { AtomicLong() }.incrementAndGet()
                        firstRefused.merge(problem, entry.stream.commitTime, ::minOf)
                    }
                }
            }
        }
        return ExportScan(
            context = context,
            heads = heads,
            streams = streams.mapValues { it.value.get() }.toSortedMap(compareBy { it.toString() }),
            missing = missing.get(),
            problems = problems.mapValues { it.value.get() }.toSortedMap(),
            conflicts = conflicts.sorted(),
            firstRefused = firstRefused.toSortedMap(),
        )
    }

    /** Writes to 9.x the streams of [scan]'s context it lacks, up to the scan's heads; [scan] must have passed. */
    suspend fun export(scan: ExportScan): ExportProgress {
        check(scan.passed) { "The export scan of ${scan.context} did not pass." }
        val context = scan.context
        val log = logOf(context)
        val startedAt = clock()
        val start = (checkpoints.load().exportOf(context) ?: ExportCheckpoint())
            .let { if (options.restart) ExportCheckpoint() else it }
        save(context) { start }
        val exported = AtomicLong()
        val already = AtomicLong()
        fun report(done: Boolean) = ExportProgress(
            context,
            start.exported + exported.get(),
            start.already + already.get(),
            clock() - startedAt,
            done,
        )
        forEachPartition(scan.heads) { partition, head ->
            walk(log, partition, start.partitions[partition], head) { page ->
                val missing = missingOf(page) { throw MigrationException("$it: 9.x was written since the scan.") }
                already.addAndGet((page.size - missing.size).toLong())
                val written = write(missing)
                exported.addAndGet(written.toLong())
                already.addAndGet((missing.size - written).toLong())
                save(context) { checkpoint ->
                    checkpoint.copy(
                        partitions = checkpoint.partitions + (partition to page.last().position.sequence),
                        exported = checkpoint.exported + written,
                        already = checkpoint.already + page.size - written,
                    )
                }
                progress(report(done = false))
            }
        }
        return report(done = true).also(progress)
    }

    /** Each 9.x aggregate mapped into [scan]'s context, with its stream count now and V10's at the scan. */
    fun verify(scan: ExportScan): List<ExportCount> = plan.aggregates.values
        .filter { it.target.context == scan.context }
        .map { ExportCount(it.source, it.target, scan.streams[it.target] ?: 0, target.count(it.source)) }

    /** Writes [entries] to 9.x; returns how many it wrote, those 9.x now holds otherwise failing the export. */
    private suspend fun write(entries: List<LogEntry>): Int = entries.groupBy {
        checkNotNull(
            reverter.aggregateOf(it.stream.ref.type)
        )
    }
        .entries.sumOf { (aggregate, ofAggregate) ->
            val streams = ofAggregate.map { reverter.revert(it.stream) }
            val held = blocking { target.insert(aggregate.source, streams) }
            if (held.isNotEmpty()) {
                // Written by a run stopped before its checkpoint, or by 9.x since the scan: only the former is fine.
                val stored = blocking { target.stored(aggregate.source, held) }
                ofAggregate.filter { NineXStreamKey(it.stream.ref.id, it.stream.version) in held }.forEach { entry ->
                    conflictOf(entry, stored[NineXStreamKey(entry.stream.ref.id, entry.stream.version)])?.let {
                        throw MigrationException("$it: 9.x was written since the scan.")
                    }
                }
            }
            streams.size - held.size
        }

    /**
     * The entries of [page] whose streams 9.x does not hold; [conflict] gets each 9.x holds otherwise. An entry of a
     * type no single 9.x aggregate maps to is missing: it has no 9.x form to be found in.
     */
    private suspend fun missingOf(page: List<LogEntry>, conflict: (String) -> Unit): List<LogEntry> =
        page.groupBy { reverter.aggregateOf(it.stream.ref.type) }.flatMap { (aggregate, entries) ->
            if (aggregate == null) {
                return@flatMap entries
            }
            val stored = blocking {
                target.stored(aggregate.source, entries.map { NineXStreamKey(it.stream.ref.id, it.stream.version) })
            }
            entries.filter { entry ->
                val held = stored[NineXStreamKey(entry.stream.ref.id, entry.stream.version)] ?: return@filter true
                conflictOf(entry, held)?.let(conflict)
                false
            }
        }

    private fun conflictOf(entry: LogEntry, held: NineXStoredStream?): String? {
        val stream = entry.stream
        return when {
            held == null -> "${stream.ref} v${stream.version} is not in 9.x"
            held.tenantId == stream.ref.tenantId && held.commandId == stream.commandId -> null
            else ->
                "${stream.ref} v${stream.version} is command ${stream.commandId} in V10 and command " +
                    "${held.commandId} of tenant ${held.tenantId} in 9.x"
        }
    }

    private suspend fun forEachPartition(heads: Map<Int, Long>, block: suspend (Int, Long) -> Unit) {
        val permits = Semaphore(options.concurrency)
        coroutineScope {
            heads.map { (partition, head) -> async { permits.withPermit { block(partition, head) } } }.awaitAll()
        }
    }

    /** Reads [partition] after [after] up to [head] a page at a time. */
    private suspend fun walk(
        log: EventLog,
        partition: Int,
        after: Long?,
        head: Long,
        page: suspend (List<LogEntry>) -> Unit,
    ) {
        if (after != null && after >= head) {
            return
        }
        val buffer = ArrayList<LogEntry>(options.pageSize)
        log.tail(partition, after).transformWhile { entry ->
            emit(entry)
            entry.position.sequence < head
        }.collect { entry ->
            buffer += entry
            if (buffer.size == options.pageSize) {
                page(buffer.toList())
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) {
            page(buffer.toList())
        }
    }

    private fun save(context: String, change: (ExportCheckpoint) -> ExportCheckpoint) {
        if (!options.dryRun) {
            checkpoints.update { it.withExport(context, change(it.exportOf(context) ?: ExportCheckpoint())) }
        }
    }

    private suspend fun <T> blocking(read: () -> T): T = withContext(Dispatchers.IO) { read() }
}
