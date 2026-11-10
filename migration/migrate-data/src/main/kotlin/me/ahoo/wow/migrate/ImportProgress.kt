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
 * What one import or tail pass of [aggregate] counted since [startedAt]: what it walked, imported, skipped and
 * rewrote, and the time its workers spent in each phase ([timed]); [report] reads them as its [ImportProgress].
 */
internal class ImportStats(val aggregate: NineXAggregate, val startedAt: Long, private val clock: () -> Long) {
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

private const val NANOS_PER_MILLI = 1_000_000L
