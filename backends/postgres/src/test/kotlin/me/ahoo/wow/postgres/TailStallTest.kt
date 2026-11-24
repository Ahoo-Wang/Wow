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

package me.ahoo.wow.postgres

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** A tail that reads nothing behind its partition's counter warns once the stall lasts, and again while it lasts. */
class TailStallTest {
    private val time = TestTimeSource()
    private val stall = TailStall("ordering", 3, 30.seconds, time)

    private val stalled = PostgresTailReads.TailBatch(emptyList(), again = false, behind = true, head = 9)

    @Test
    fun `a stall warns after its time, naming the partition, the sequence and the counter, and again each time`() {
        val warnings = captured {
            stall.observe(stalled, 6)
            time += 29.seconds
            stall.observe(stalled, 6)
            time += 1.seconds
            stall.observe(stalled, 6)
            time += 29.seconds
            stall.observe(stalled, 6)
            time += 1.seconds
            stall.observe(stalled, 6)
        }
        warnings.map { it.level }.assert().containsExactly(Level.WARN, Level.WARN)
        warnings.map { it.formattedMessage }.assert().containsExactly(
            "The tail of partition 3 of the log of ordering has waited 30s for sequence 7: the partition's counter is " +
                "at 9, but no log table that wow_log_aggregate lists holds that entry, so its subscriptions read no " +
                "further. Its table has no record there, or the entry was deleted.",
            "The tail of partition 3 of the log of ordering has waited 1m for sequence 7: the partition's counter is " +
                "at 9, but no log table that wow_log_aggregate lists holds that entry, so its subscriptions read no " +
                "further. Its table has no record there, or the entry was deleted.",
        )
    }

    @Test
    fun `a read that finds entries, or one not behind its counter, ends the stall, and the next starts afresh`() {
        val warnings = captured {
            stall.observe(stalled, 6)
            time += 20.seconds
            stall.observe(PostgresTailReads.TailBatch(listOf(entry(7)), again = false, behind = true, head = 9), 6)
            stall.observe(stalled, 7)
            time += 20.seconds
            stall.observe(PostgresTailReads.TailBatch(emptyList(), again = false, behind = false, head = 7), 7)
            stall.observe(stalled, 7)
            time += 20.seconds
            stall.observe(stalled, 7)
        }
        warnings.assert().isEmpty()
    }

    private fun captured(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(PostgresEventLog::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list
    }

    private fun entry(sequence: Long): LogEntry {
        val ref = AggregateType("ordering", "order").ref("tenant", "o-1")
        val stream = EventStreamRecord(
            ref,
            1,
            "c-1",
            "r-1",
            "ordering.order.Act",
            1,
            listOf(EventRecord("ordering.order.Acted", 1, "{}")),
            Scope.NONE,
        )
        return LogEntry(LogPosition(3, sequence), stream, null)
    }
}
