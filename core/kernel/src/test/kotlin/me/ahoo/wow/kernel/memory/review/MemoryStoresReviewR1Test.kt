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

package me.ahoo.wow.kernel.memory.review

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.access.AggregatePage
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Deep review round 1 of the test kit: where the kernel's memory event log ([InMemoryEventLog]), which every domain,
 * processor and service test runs on, answers otherwise than the PostgreSQL and MongoDB logs a node runs on. Each case
 * asserts what production does, so it fails on the memory log until the two agree.
 */
class MemoryStoresReviewR1Test {
    private val type = AggregateType("review", "account")
    private val other = AggregateType("elsewhere", "account")

    @Test
    fun `a group whose streams of one aggregate skip a version should be refused before anything is written`() {
        // PostgresEventLog.appendAll (backends/postgres/.../PostgresEventLog.kt:172-175) and MongoEventLog.appendAll
        // (backends/mongo/.../log/MongoEventLog.kt:326-329) call requireChains (core/spi/.../AppendOutcome.kt:155-165)
        // before they write: a group of a@1 and a@3 throws IllegalArgumentException and writes nothing. The memory
        // log takes EventLog.appendAll's default (core/spi/.../log/EventLog.kt:91-116): it commits a@1, conflicts a@3.
        val log = InMemoryEventLog(partitions = 1)
        runBlocking {
            assertThrows<IllegalArgumentException> {
                log.appendAll(listOf(Append.of(stream("a", 1)), Append.of(stream("a", 3))))
            }
            log.head(0).assert().describedAs("the partition's head after the refused group").isNull()
            log.read(type.ref("tenant", "a")).toList().assert().isEmpty()
        }
    }

    @Test
    fun `a group that holds the streams of two bounded contexts should be refused before anything is written`() {
        // A production log is one context's: PostgresEventLog.appendAll requires every stream of its context
        // (PostgresEventLog.kt:172-174, "A group commit appends streams of $context."), as MongoEventLog.appendAll
        // does (MongoEventLog.kt:326-328), so a group of two contexts is refused whichever context the log is. The
        // memory log has no context (InMemoryEventLog.kt:48; the host opens one per context, HostStores.kt:94) and
        // commits both.
        val log = InMemoryEventLog(partitions = 1)
        runBlocking {
            assertThrows<IllegalArgumentException> {
                log.appendAll(listOf(Append.of(stream("a", 1)), Append.of(stream("b", 1, aggregate = other))))
            }
            log.head(0).assert().describedAs("the partition's head after the refused group").isNull()
        }
    }

    @Test
    fun `an aggregate whose first append was refused should not be listed among the type's aggregates`() {
        // PostgreSQL lists an aggregate by its version-1 row (backends/postgres/.../PostgresEventLogTables.kt:457-462,
        // `WHERE version = 1`, read by PostgresLogAccess.kt:60-79), MongoDB by its first stream
        // (backends/mongo/.../log/MongoLogAccess.kt:116-135): an aggregate that never committed is not one. The
        // memory log creates the aggregate's entry before its version check (InMemoryEventLog.kt:82 before :84) and
        // lists every entry (:121-128).
        val log = InMemoryEventLog(partitions = 1)
        runBlocking {
            log.append(stream("ghost", 2)).assert().isEqualTo(AppendOutcome.Conflict(0))
            log.aggregates(type, null, limit = 3).assert().isEqualTo(AggregatePage(emptyList(), null))
            log.append(stream("a-1", 1)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            log.aggregates(type, null, limit = 3).refs.assert().containsExactly(type.ref("tenant", "a-1"))
        }
    }

    private fun stream(id: String, version: Int, aggregate: AggregateType = type) = EventStreamRecord(
        ref = aggregate.ref("tenant", id),
        version = version,
        commandId = "command-$id-$version",
        requestId = "request-$id-$version",
        commandType = "${aggregate.context}.${aggregate.name}.Act",
        commitTime = 1_700_000_000_000L + version,
        events = listOf(EventRecord("${aggregate.context}.${aggregate.name}.Acted", 1, """{"version":$version}""")),
        scope = Scope("owner", "space"),
    )
}
