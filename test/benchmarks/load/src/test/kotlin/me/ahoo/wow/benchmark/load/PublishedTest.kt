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

package me.ahoo.wow.benchmark.load

import me.ahoo.test.asserts.assert
import me.ahoo.wow.benchmark.load.PublishedStream.Sample
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PublishedTest {
    private val startedAt = 10_000L
    private val stoppedAt = 13_000L
    private val drainedAt = 13_200L

    /**
     * Partition 0 takes the load and its sink falls behind, then catches up after it; partition 1 is idle. Rounds a
     * second from 9.8 s: the heads, the checkpoints and the oldest commit above each checkpoint (-1: none).
     */
    private val rounds = listOf(9_800L, 10_800L, 11_800L, 12_800L, 13_800L, 14_800L, 15_800L)
    private val heads = listOf(100L, 200L, 300L, 400L, 450L, 450L, 450L)
    private val checkpoints = listOf(100L, 150L, 200L, 250L, 300L, 400L, 450L)
    private val oldest = listOf(-1L, 10_000L, 10_300L, 10_800L, 11_300L, 12_800L, -1L)

    private fun samples(count: Int = rounds.size, checkpointed: Boolean = true): List<Sample> =
        (0 until count).flatMap { round ->
            listOf(
                Sample(
                    rounds[round],
                    0,
                    heads[round],
                    checkpoints[round].takeIf { checkpointed },
                    oldest[round].takeIf { it >= 0 },
                    "account-$round".takeIf { oldest[round] >= 0 },
                ),
                Sample(rounds[round], 1, 10, 10L.takeIf { checkpointed }, null, null),
            )
        }

    @Test
    fun `a read of either store should be parsed, a checkpoint not saved and nothing waiting as null`() {
        PublishedStream.parse("1764140000.5|3|120|100|1764139999000|account-7")
            .assert().isEqualTo(Sample(1_764_140_000_500, 3, 120, 100, 1_764_139_999_000, "account-7"))
        PublishedStream.parse("1764140000|0|5|-1|-1|")
            .assert().isEqualTo(Sample(1_764_140_000_000, 0, 5, null, null, null))
        PublishedStream.parse("ERROR:  relation \"wow_checkpoint\" does not exist").assert().isNull()
        PublishedStream.parse("1764140000|0|5|-1|-1").assert().isNull()
    }

    @Test
    fun `a stream should be named context dot aggregate, of names a statement can hold`() {
        PublishedStream.of("transfer.account").let {
            it.context.assert().isEqualTo("transfer")
            it.aggregate.assert().isEqualTo("account")
            it.subscription.assert().isEqualTo("wow.transport-sink.account")
            it.postgresQuery().assert().contains("from \"wow_log__transfer__partition\" p")
                .contains("from \"wow_log__transfer__account\" l")
                .contains("where subscription = 'wow.transport-sink.account'")
            it.mongoLoop().assert().contains("db.getCollection('wow_log__transfer__account')")
                .contains("db.getCollection('wow_log__transfer__partition')")
                .contains("saved.find({subscription: 'wow.transport-sink.account'})")
        }
        assertThrows<IllegalArgumentException> { PublishedStream.of("transfer") }
        assertThrows<IllegalArgumentException> { PublishedStream.of("a.b.c") }
        assertThrows<IllegalArgumentException> { PublishedStream.of("transfer.account' or 'x") }
    }

    @Test
    fun `the summary should give the appends, the publications, the worst lags and the catch-up`() {
        val summary = PublishedStream.summary(samples(), startedAt, stoppedAt, drainedAt)

        summary["partitions"].assert().isEqualTo(2)
        summary["checkpointed"].assert().isEqualTo(true)
        // From the round at 9.8 s to the one at 13.8 s: 4 seconds.
        summary["appendedPerSecond"].assert().isEqualTo(87.5)
        summary["publishedPerSecond"].assert().isEqualTo(50.0)
        summary["busiestPartition"].assert()
            .isEqualTo(mapOf("partition" to 0, "appendedPerSecond" to 87.5, "publishedPerSecond" to 50.0))
        summary["maxLag"].assert().isEqualTo(mapOf("entries" to 150L, "partition" to 0, "t" to 2.8))
        summary["maxLagSeconds"].assert()
            .isEqualTo(mapOf("seconds" to 2.5, "partition" to 0, "aggregate" to "account-4", "t" to 3.8))
        // The heads once the drain ended (the round at 13.8 s) all published at 15.8 s.
        summary["catchUpSeconds"].assert().isEqualTo(2.8)
        summary.containsKey("lagAtEnd").assert().isFalse()
        summary["readsUntilSecond"].assert().isEqualTo(5.8)
        summary["series"].assert().isEqualTo(
            mapOf(
                "t" to listOf(0.8, 1.8, 2.8, 3.8),
                "partitions" to listOf(
                    mapOf(
                        "partition" to 0,
                        "appended" to listOf(100.0, 100.0, 100.0, 50.0),
                        "published" to listOf(50.0, 50.0, 50.0, 50.0),
                        "lag" to listOf(50L, 100L, 150L, 150L),
                        "lagSeconds" to listOf(0.8, 1.5, 2.0, 2.5),
                    ),
                    mapOf(
                        "partition" to 1,
                        "appended" to listOf(0.0, 0.0, 0.0, 0.0),
                        "published" to listOf(0.0, 0.0, 0.0, 0.0),
                        "lag" to listOf(0L, 0L, 0L, 0L),
                        "lagSeconds" to listOf(0.0, 0.0, 0.0, 0.0),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a stream that had not caught up when the reads stopped should say how far behind it was`() {
        val samples = samples(count = 6)

        PublishedStream.caughtUpAt(samples, stoppedAt, drainedAt).assert().isNull()
        val summary = PublishedStream.summary(samples, startedAt, stoppedAt, drainedAt)
        summary["catchUpSeconds"].assert().isNull()
        summary["lagAtEnd"].assert().isEqualTo(50L)
    }

    @Test
    fun `the catch-up should be against the heads once the drain ended, not those at the stop`() {
        // The drain's appends end at 450, published at 15.8 s; the heads of the round before the stop, 400, at 14.8 s.
        PublishedStream.caughtUpAt(samples(), stoppedAt, drainedAt).assert().isEqualTo(15_800L)
        PublishedStream.caughtUpAt(samples(), stoppedAt, drainedAt = 12_000L).assert().isEqualTo(14_800L)
        // Nothing read since the drain ended: no heads to catch up with yet.
        PublishedStream.caughtUpAt(samples(count = 4), stoppedAt, drainedAt).assert().isNull()
    }

    @Test
    fun `a sink that saved no checkpoint should be said so, every lag then the head`() {
        val summary = PublishedStream.summary(samples(checkpointed = false), startedAt, stoppedAt, drainedAt)

        summary["checkpointed"].assert().isEqualTo(false)
        summary["publishedPerSecond"].assert().isEqualTo(0.0)
        (summary["maxLag"] as Map<*, *>)["entries"].assert().isEqualTo(450L)
        summary["catchUpSeconds"].assert().isNull()
    }

    @Test
    fun `no read at all should be said so`() {
        PublishedStream.summary(emptyList(), startedAt, stoppedAt, drainedAt)
            .assert().isEqualTo(mapOf("missing" to "no read of the published stream answered"))
    }

    @Test
    fun `the report's line should give the stream's figures, or why it has none`() {
        val names = mapOf(
            "stream" to "transfer.account",
            "subscription" to "wow.transport-sink.account",
            "catchUpLimitSeconds" to 60,
        )
        val stream = names + PublishedStream.summary(samples(), startedAt, stoppedAt, drainedAt)
        Report.publishedLine(stream).assert().isEqualTo(
            "Published stream transfer.account: appended 88/s, published 50/s; worst lag 150 entries (partition 0), " +
                "2.5 s (aggregate account-4); caught up 2.8 s after the load",
        )
        val unsaved = samples(count = 6, checkpointed = false)
        val behind = names + PublishedStream.summary(unsaved, startedAt, stoppedAt, drainedAt)
        Report.publishedLine(behind).assert().isEqualTo(
            "Published stream transfer.account (no checkpoint of wow.transport-sink.account: every lag is the head): " +
                "appended 88/s, published 0/s; worst lag 450 entries (partition 0), 2.5 s (aggregate account-4); " +
                "not caught up 60 s after the drain, 460 entries behind",
        )
        val missing = mapOf(
            "stream" to "transfer.account",
            "missing" to "no read of the published stream answered",
        )
        Report.publishedLine(missing).assert()
            .isEqualTo("Published stream transfer.account: no read of the published stream answered")
    }
}
