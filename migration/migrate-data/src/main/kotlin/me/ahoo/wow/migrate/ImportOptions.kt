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

/**
 * How [EventStreamImporter] runs.
 *
 * - [pageSize] instances are imported between two checkpoints. A page is read with its instances' streams in one scan
 *   ([NineXEventSource.instances]), at most [pageStreams] of them, so an estate of large instances has shorter pages;
 *   the streams it read are appended in [concurrency] groups of whole instances, each one call of the log
 *   ([EventLog.appendAll]); the rest of an instance it read in part is read [streamBatch] streams at a time. The next
 *   page is read while a page is appended, and a page's groups start while the last page's finish, [concurrency]
 *   groups at a time in all; the checkpoint moves after a page once it and every page before it are imported. The tail
 *   reads [pageSize] streams at once, and imports [concurrency] instances at a time.
 * - [dryRun] reads and converts every stream but appends none and saves no checkpoint: the mapping check's dry run
 *   extended to every payload.
 * - [restart] walks every instance again, ignoring where the last full import stopped: the final complete pass once
 *   9.x has stopped writing (step 3).
 * - [lookbackMillis] is how far before the last commit time it read the tail scans again: 9.x nodes' clocks differ and
 *   a stream becomes visible after its `createTime`, so a stream committed earlier may appear later. A pass ends at the
 *   latest stream committed when it starts ([EventStreamImporter.tailOnce]), so a stream is read by a later pass when
 *   it becomes visible within the look-back of its commit time, the 9.x nodes' clock differences included.
 * - [maxUnknownRetries] is how often an append whose outcome is unknown is tried again.
 * - [readParallelism] is how many ranges of ids the full import walks at once, each its own scan of the 9.x store,
 *   split by ids sampled from it ([NineXEventSource.idBoundaries]) when the import first starts. The 9.x store serves
 *   production through the dual run, and each range is one more scan it runs. In a 500,000-order bench 2 ranges took
 *   most of the gain (7 to 16% against 1, 8 ranges 3 to 4% more) for little more of MongoDB's own CPU, while a probe of
 *   9.x's reads saw its p99 grow 3 to 5 times: keep 2 (or 1) through the dual run, and raise it once 9.x has stopped.
 */
internal data class ImportOptions(
    val pageSize: Int = 500,
    val streamBatch: Int = 200,
    val concurrency: Int = 8,
    val dryRun: Boolean = false,
    val restart: Boolean = false,
    val lookbackMillis: Long = 300_000,
    val maxUnknownRetries: Int = 3,
    val readParallelism: Int = DEFAULT_READ_PARALLELISM,
) {
    init {
        require(readParallelism >= 1) { "The walk reads at least one range at a time." }
        require(pageSize >= 1) { "A page holds at least one instance." }
        require(streamBatch >= 1) { "A batch holds at least one stream." }
        require(concurrency >= 1) { "At least one instance at a time." }
        require(lookbackMillis >= 0) { "The look-back window is never negative." }
        require(maxUnknownRetries >= 0) { "The retries are never negative." }
    }

    /** The streams a page of the full import reads at most: [STREAMS_PER_INSTANCE] for each of its [pageSize]. */
    val pageStreams: Int get() = pageSize * STREAMS_PER_INSTANCE

    companion object {
        /** A page's share of streams per instance: 9.x instances hold a few streams, often fewer than ten. */
        private const val STREAMS_PER_INSTANCE = 10

        /** The ranges the full import walks at once by default. */
        const val DEFAULT_READ_PARALLELISM = 2
    }
}
