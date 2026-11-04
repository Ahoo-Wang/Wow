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
import org.junit.jupiter.api.Test

class SamplesTest {
    private val names = listOf("epoch", "xact_commit", "wal_sync_time")

    @Test
    fun `columns parses a sampler line and rejects anything else`() {
        Samples.columns("1760000000.25|120|3.5", names, '|').assert().isEqualTo(
            mapOf("epoch" to 1760000000.25, "xact_commit" to 120.0, "wal_sync_time" to 3.5),
        )
        Samples.columns("Mon Oct 20 (every 1s)", names, '|').assert().isNull()
        Samples.columns("1|2", names, '|').assert().isNull()
        Samples.columns("", names, '|').assert().isNull()
        Samples.at(mapOf("epoch" to 1760000000.25)).assert().isEqualTo(1760000000250L)
    }

    @Test
    fun `the edges are the samples nearest the window`() {
        val samples = listOf(1_000L to "a", 2_000L to "b", 3_000L to "c")
        Samples.atOrBefore(samples, 2_500L)!!.second.assert().isEqualTo("b")
        Samples.atOrAfter(samples, 2_500L)!!.second.assert().isEqualTo("c")
        // Before the first sample or after the last, the nearest one there is.
        Samples.atOrBefore(samples, 500L)!!.second.assert().isEqualTo("a")
        Samples.atOrAfter(samples, 9_000L)!!.second.assert().isEqualTo("c")
        Samples.within(samples, 1_500L..3_000L).assert().isEqualTo(listOf("b", "c"))
    }

    @Test
    fun `perSecond gives each key's rate between consecutive samples across the window`() {
        val samples = listOf(
            0L to mapOf("xact_commit" to 0.0),
            1_000L to mapOf("xact_commit" to 100.0),
            2_000L to mapOf("xact_commit" to 300.0),
            4_000L to mapOf("xact_commit" to 700.0),
            5_000L to mapOf("xact_commit" to 800.0),
        )
        val series = Samples.perSecond(samples, 1_500L..3_500L, listOf("xact_commit"))
        series.map { it["t"] }.assert().isEqualTo(listOf(0.5, 2.5))
        series.map { it["xact_commit"] }.assert().isEqualTo(listOf(200.0, 200.0))
    }

    @Test
    fun `procStat reads CPU and resident memory after a command name with spaces`() {
        val fields = (3..52).map { it.toString() }.toMutableList()
        // Fields 14 and 15 (utime, stime) and 24 (rss), counted from 1 with pid and comm.
        fields[14 - 3] = "250"
        fields[15 - 3] = "50"
        fields[24 - 3] = "2560"
        val stat = "4242 (java main thread) " + fields.joinToString(" ")
        Samples.procStat(stat, clockTicks = 100.0, pageKb = 4.0).assert().isEqualTo(
            mapOf("cpuSeconds" to 3.0, "rssMb" to 10.0),
        )
        Samples.procStat("4242 (java) S 1", 100.0, 4.0).assert().isNull()
    }

    @Test
    fun `diskStats reads each disk's writes and flushes, without partitions, loops or RAM`() {
        val text = """
           7       0 loop0 10 0 20 1 0 0 0 0 0 1 1 0 0 0 0 0 0
           8       0 sda 100 5 2000 40 300 20 4000 900 0 1200 1500 0 0 0 0 250 600
           8       1 sda1 90 5 1900 38 290 20 3900 880 0 1100 1400 0 0 0 0 0 0
         259       0 nvme0n1 1 0 8 0 50 0 800 25 1 30 40
         259       1 nvme0n1p1 1 0 8 0 50 0 800 25 0 30 40
        """.trimIndent()
        Samples.diskStats(text).assert().isEqualTo(
            mapOf(
                "sda:writes" to 300.0,
                "sda:writeKb" to 2000.0,
                "sda:writeMs" to 900.0,
                "sda:ioMs" to 1200.0,
                "sda:flushes" to 250.0,
                "sda:flushMs" to 600.0,
                // A kernel before 5.5 has no flush counters.
                "nvme0n1:writes" to 50.0,
                "nvme0n1:writeKb" to 400.0,
                "nvme0n1:writeMs" to 25.0,
                "nvme0n1:ioMs" to 30.0,
                "nvme0n1:flushes" to 0.0,
                "nvme0n1:flushMs" to 0.0,
            ),
        )
        Samples.diskStats("").assert().isEqualTo(emptyMap<String, Double>())
    }

    @Test
    fun `whole keeps counters integral`() {
        Samples.whole(12.0).assert().isEqualTo(12L)
        Samples.whole(1.5).assert().isEqualTo(1.5)
    }
}
