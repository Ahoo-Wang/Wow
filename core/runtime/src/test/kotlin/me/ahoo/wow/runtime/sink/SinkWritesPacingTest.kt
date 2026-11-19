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

package me.ahoo.wow.runtime.sink

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.source.orderRecord
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A paced sink's writes ([Sink.minWriteInterval]) on virtual time: when each starts and what it carries. */
class SinkWritesPacingTest {
    /** Records each write's start (virtual milliseconds) and records; [failing] fails the next that many writes. */
    private class Timed(private val scope: TestScope) : Sink {
        val writes = ArrayList<Pair<Long, List<String>>>()
        var failing = 0

        override suspend fun write(records: List<StateEventRecord>) {
            writes += scope.testScheduler.currentTime to records.map { "${it.ref.id}@${it.version}" }
            if (failing > 0) {
                failing--
                throw StorageUnavailableException("ClickHouse is unavailable; retry later.")
            }
        }
    }

    private fun TestScope.paced(sink: Sink, maxBatch: Int = 8) =
        SinkWrites(sink, maxBatch, INTERVAL, testScheduler.timeSource)

    @Test
    fun `an idle sink's write starts at once, and so does one after a pause of the interval`() = runTest {
        val sink = Timed(this)
        val writes = paced(sink)
        writes.write(listOf(orderRecord("a", 1))).done.assert().isEqualTo(1)
        delay(INTERVAL)
        writes.write(listOf(orderRecord("a", 2))).done.assert().isEqualTo(1)
        sink.writes.map { it.first }.assert().containsExactly(0L, INTERVAL.inWholeMilliseconds)
    }

    @Test
    fun `under a steady arrival one write per interval carries everything that arrived, each at most one late`() =
        runTest {
            val sink = Timed(this)
            val writes = paced(sink, maxBatch = 1000)
            // A commit of another aggregate every 100 ms for 5 s: 50 runs.
            val lanes = (0 until 50).map { index ->
                async {
                    delay(index * 100L)
                    val arrived = testScheduler.currentTime
                    writes.write(listOf(orderRecord("o$index", 1))).done.assert().isEqualTo(1)
                    testScheduler.currentTime - arrived
                }
            }
            val waits = lanes.awaitAll()
            val starts = sink.writes.map { it.first }
            // The first write at once, then one each interval: 0, 1000, ..., 5000.
            starts.assert().containsExactly(0L, 1000L, 2000L, 3000L, 4000L, 5000L)
            sink.writes.sumOf { it.second.size }.assert().isEqualTo(50)
            waits.max().assert().isLessThanOrEqualTo(INTERVAL.inWholeMilliseconds)
        }

    @Test
    fun `a full batch waiting is written at once, without the interval`() = runTest {
        val sink = Timed(this)
        val writes = paced(sink, maxBatch = 4)
        writes.write(listOf(orderRecord("a", 1)))
        delay(100.milliseconds)
        // Four records waiting are a full batch: written at 100 ms, not at 1,000.
        listOf(
            async { writes.write(listOf(orderRecord("b", 1), orderRecord("b", 2))) },
            async { writes.write(listOf(orderRecord("c", 1), orderRecord("c", 2))) },
        ).awaitAll().forEach { it.done.assert().isEqualTo(2) }
        sink.writes.map { it.first }.assert().containsExactly(0L, 100L)
        sink.writes.last().second.assert().containsExactly("b@1", "b@2", "c@1", "c@2")
    }

    @Test
    fun `a paced write that failed is retried as the same batch, so with the same token, an interval later`() =
        runTest {
            val sink = Timed(this).apply { failing = 1 }
            val writes = paced(sink)
            val run = listOf(orderRecord("a", 1), orderRecord("a", 2))
            writes.write(run).failure.assert().isInstanceOf(StorageUnavailableException::class.java)
            // The lane retries its run at once: the write starts an interval after the failed one started, and holds
            // the same records, which a sink's token of the batch (the ClickHouse Sink's digest of its ids) repeats.
            writes.write(run).failure.assert().isNull()
            sink.writes.map { it.first }.assert().containsExactly(0L, INTERVAL.inWholeMilliseconds)
            sink.writes[1].second.assert().isEqualTo(sink.writes[0].second)
        }

    @Test
    fun `a negative interval is refused, and paced writes run one at a time`() {
        assertThrows<IllegalArgumentException> { SinkWrites(Timed(TestScope()), 8, (-1).seconds) }
    }

    companion object {
        private val INTERVAL = 1.seconds
    }
}
