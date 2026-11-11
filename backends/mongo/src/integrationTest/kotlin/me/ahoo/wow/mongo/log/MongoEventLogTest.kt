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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.CountedMongo
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.tck.log.EventLogSpec
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class MongoEventLogTest : EventLogSpec() {
    override fun createLog(context: String, partitions: Int): EventLog = MongoLogFixture.log(partitions, context)

    override val opensOneStorePerContext: Boolean = true

    override fun createSnapshots(context: String): SnapshotStore =
        MongoSnapshotStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context).also { it.createSchema() }

    /** A decimal of 40 significant digits: MongoDB keeps at most 34 exactly. */
    override fun refused(stream: EventStreamRecord): EventStreamRecord = stream.copyWith(
        events = listOf(
            EventRecord("x.aggregate.Priced", 1, """{"amount":1.234567890123456789012345678901234567890}""")
        ),
    )

    @Test
    fun `a commit of this log costs its idle tail one read`(): Unit = runBlocking {
        // The poll a commit wakes finds the entry and keeps the backoff, without a run of short polls. A read the
        // commit woke before a majority had the entry finds nothing and waits, so it is not counted as polling on.
        val context = MongoLogFixture.newContext()
        val log = MongoEventLog(
            CountedMongo.client,
            MongoLogFixture.DATABASE,
            context,
            1,
            options = MongoEventLogOptions(pollInterval = 1.milliseconds, maxPollInterval = 1.hours),
        ).also { it.createSchema() }
        val type = AggregateType(context, "order")
        log.append(stream("a", 1, aggregate = type))
        val counters = collection(context, "partition")
        fun reads(mark: Int) = CountedMongo.since(mark).count { it.name == "aggregate" && it.collection == counters }
        val start = CountedMongo.begin()
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
            for (version in 2..COMMITS + 1) {
                val mark = CountedMongo.begin()
                log.append(stream("a", version, aggregate = type))
                withTimeout(TAIL_TIMEOUT_MS) { received.receive() }.stream.version.assert().isEqualTo(version)
                // The read that found the entry, after any that ran before a majority had it (a loaded runner).
                reads(mark).assert().isGreaterThanOrEqualTo(1)
                // Then the tail keeps its backoff: no run of short polls until the next commit.
                val found = CountedMongo.begin()
                delay(SETTLE_MS)
                reads(found).assert().isZero()
            }
        } finally {
            tail.cancel()
        }
    }

    private companion object {
        const val IDLE_POLLS = 13
        const val COMMITS = 5
        const val SETTLE_MS = 100L
    }
}
