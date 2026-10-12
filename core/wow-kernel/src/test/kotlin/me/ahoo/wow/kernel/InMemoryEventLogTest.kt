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

package me.ahoo.wow.kernel

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test

class InMemoryEventLogTest {
    private val type = AggregateType("example", "cart")

    private fun stream(id: String, version: Int, requestId: String = "$id-$version", tenant: String = "t") =
        EventStreamRecord(
            ref = type.ref(tenant, id),
            version = version,
            commandId = "c-$requestId",
            requestId = requestId,
            fingerprint = "f",
            commitTime = 0,
            events = listOf(EventRecord("example.cart.ItemAdded", 1, "{}")),
        )

    @Test
    fun `versions start at 1 and grow by one`() = runTest {
        val log = InMemoryEventLog()
        log.append(stream("a", 2)).assert().isEqualTo(AppendOutcome.Conflict(0))
        log.append(stream("a", 1)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.append(stream("a", 1, requestId = "other")).assert().isEqualTo(AppendOutcome.Conflict(1))
    }

    @Test
    fun `a request id is unique within its tenant only`() = runTest {
        val log = InMemoryEventLog()
        val first = stream("a", 1, requestId = "r")
        log.append(first)
        log.append(stream("b", 1, requestId = "r")).assert().isEqualTo(AppendOutcome.AlreadyCommitted(first))
        log.append(stream("a", 1, requestId = "r", tenant = "other"))
            .assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    @Test
    fun `read returns a version range in order`() = runTest {
        val log = InMemoryEventLog()
        (1..4).forEach { log.append(stream("a", it)) }
        log.read(type.ref("t", "a"), 2, 3).toList().map { it.version }.assert().containsExactly(2, 3)
    }

    @Test
    fun `tail replays committed entries then waits for new ones`() = runTest {
        val log = InMemoryEventLog(partitions = 1)
        log.append(stream("a", 1))
        val tailed = mutableListOf<Long>()
        val reader = launch { log.tail(0).take(3).toList().forEach { tailed += it.position.sequence } }
        yield()
        log.append(stream("a", 2))
        log.append(stream("b", 1))
        reader.join()
        tailed.assert().containsExactly(1L, 2L, 3L)
        log.tail(0, after = 2).take(1).toList().single().stream.ref.id.assert().isEqualTo("b")
    }

    @Test
    fun `an entry lands in its ref's partition`() = runTest {
        val log = InMemoryEventLog(partitions = 8)
        val committed = log.append(stream("a", 1)) as AppendOutcome.Committed
        committed.position.partition.assert().isEqualTo(Partitioner.partitionOf(type.ref("t", "a"), 8))
    }
}
