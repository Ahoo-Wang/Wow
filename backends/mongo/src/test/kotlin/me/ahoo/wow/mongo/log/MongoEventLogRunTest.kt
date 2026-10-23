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

package me.ahoo.wow.mongo.log

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import org.junit.jupiter.api.Test

/** The tail's final run (decision D271): entries and placeholders from after a position, until a missing sequence. */
class MongoEventLogRunTest {
    private val type = AggregateType("shop", "order")

    private fun entry(sequence: Long) = LogEntry(
        LogPosition(0, sequence),
        EventStreamRecord(
            ref = type.ref("tenant", "o-$sequence"),
            version = 1,
            commandId = "c-$sequence",
            requestId = "r-$sequence",
            commandType = "$type.Place",
            commitTime = 0,
            events = listOf(EventRecord("$type.Placed", 1, "{}")),
            scope = Scope.NONE,
        ),
    )

    private fun entries(vararg sequences: Long) = sequences.associateWith(::entry)

    @Test
    fun `the run steps over single and range placeholders`() {
        val (run, next) = MongoEventLog.finalRun(entries(1, 3, 8), mapOf(2L to 2L, 4L to 7L), 0, 10)
        run.map { it.position.sequence }.assert().containsExactly(1L, 3L, 8L)
        next.assert().isEqualTo(9L)
    }

    @Test
    fun `the run stops at a sequence neither written nor a placeholder`() {
        val (run, next) = MongoEventLog.finalRun(entries(5, 6, 8), emptyMap(), 4, 10)
        run.map { it.position.sequence }.assert().containsExactly(5L, 6L)
        next.assert().isEqualTo(7L)
    }

    @Test
    fun `the run holds at most the limit of entries, placeholders not counted`() {
        val (run, next) = MongoEventLog.finalRun(entries(1, 3, 4), mapOf(2L to 2L), 0, 2)
        run.map { it.position.sequence }.assert().containsExactly(1L, 3L)
        next.assert().isEqualTo(4L)
    }

    @Test
    fun `a run after the last position starts at the next sequence`() {
        val (run, next) = MongoEventLog.finalRun(entries(1, 2), emptyMap(), 2, 10)
        run.assert().isEmpty()
        next.assert().isEqualTo(3L)
    }
}
