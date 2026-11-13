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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendCallMarks
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.tck.log.EventLogSpec
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class PostgresEventLogTest : EventLogSpec() {
    override fun createLog(context: String, partitions: Int): EventLog = PostgresFixture.log(partitions, context)

    override val opensOneStorePerContext: Boolean = true

    override fun createSnapshots(context: String): SnapshotStore =
        PostgresSnapshotStore(PostgresFixture.dataSource, context)

    override fun snapshotTailsOf(log: EventLog, snapshots: SnapshotStore): SnapshotTailReader =

        (log as PostgresEventLog).snapshotTailsWith(snapshots as PostgresSnapshotStore)

    /** A `\u0000` in a payload string: PostgreSQL's `jsonb` does not take it (finding A2-7). */
    override fun refused(stream: EventStreamRecord): EventStreamRecord = stream.copyWith(
        events = listOf(EventRecord("x.aggregate.Named", 1, """{"name":"a\u0000b"}""")),
    )

    @Test
    fun `a commit of this log costs its idle tail one read`(): Unit = runBlocking {
        // B.D diagnostics: the poll a commit wakes finds the entry and keeps the backoff, without a run of short polls.
        val counted = CountedDataSource(PostgresFixture.dataSource)
        val context = PostgresFixture.newContext()
        val log = PostgresEventLog(
            counted.dataSource,
            context,
            1,
            options = PostgresEventLogOptions(pollInterval = 1.milliseconds, maxPollInterval = 1.hours),
        ).also { it.createSchema() }
        val type = AggregateType(context, "order")
        log.append(stream("a", 1, aggregate = type))
        fun reads(mark: Int) = counted.since(mark).count { it.contains("AS head") }
        val start = counted.begin()
        val received = Channel<LogEntry>(Channel.UNLIMITED)
        val tail = launch(Dispatchers.Default) { log.tail(0).collect { received.send(it) } }
        try {
            withTimeout(TAIL_TIMEOUT_MS) { received.receive() }
            // Twelve empty polls back the tail off to seconds between polls.
            withTimeout(TAIL_TIMEOUT_MS) {
                while (reads(start) < IDLE_POLLS) {
                    delay(10)
                }
            }
            val mark = counted.begin()
            for (version in 2..COMMITS + 1) {
                log.append(stream("a", version, aggregate = type))
                withTimeout(TAIL_TIMEOUT_MS) { received.receive() }.stream.version.assert().isEqualTo(version)
            }
            delay(SETTLE_MS)
            reads(mark).assert().isEqualTo(COMMITS)
        } finally {
            tail.cancel()
        }
    }

    @Test
    fun `a clocked append should mark each instant only the log sees, in order`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val log = createLog(context, CLOCKED_PARTITIONS)
        val type = AggregateType(context, "order")
        // A group commit across partitions, and a single stream's call.
        val calls = listOf(listOf("a", "b", "c", "d"), listOf("e"))
        calls.forEach { ids ->
            val marks = AppendCallMarks()
            val outcomes = withContext(marks) {
                log.appendAll(ids.map { Append.of(stream(it, 1, aggregate = type), null) })
            }
            outcomes.forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
            marks.marked.assert().describedAs("$ids").isTrue()
            listOf(marks.taken, marks.entered, marks.onThread, marks.acquired, marks.sent, marks.read, marks.released)
                .zipWithNext().forEach { (earlier, later) -> later.assert().isGreaterThanOrEqualTo(earlier) }
        }
    }

    private companion object {
        const val CLOCKED_PARTITIONS = 4
        const val IDLE_POLLS = 13
        const val COMMITS = 5
        const val SETTLE_MS = 100L
    }
}
