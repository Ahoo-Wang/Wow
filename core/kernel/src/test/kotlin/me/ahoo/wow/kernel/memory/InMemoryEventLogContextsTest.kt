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

package me.ahoo.wow.kernel.memory

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A memory log made without a context, as a test that keeps several contexts in one log makes it: one append at a
 * time it takes any context's streams, and a group only of one context, as a durable log's group is ([InMemoryEventLog];
 * the event log contract, run on a log of one context, holds the rest).
 */
class InMemoryEventLogContextsTest {
    private val orders = AggregateType("orders", "order")
    private val billing = AggregateType("billing", "invoice")

    @Test
    fun `a log without a context should take each context's streams one append at a time`(): Unit = runBlocking {
        val log = InMemoryEventLog(partitions = 1)
        log.append(stream(orders, "o-1")).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.append(stream(billing, "b-1")).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.appendAll(listOf(Append.of(stream(orders, "o-2")), Append.of(stream(orders, "o-3"))))
            .assert().allMatch { it is AppendOutcome.Committed }
    }

    @Test
    fun `a log without a context should refuse a group of two contexts before it writes any`(): Unit = runBlocking {
        val log = InMemoryEventLog(partitions = 1)
        assertThrows<IllegalArgumentException> {
            log.appendAll(listOf(Append.of(stream(orders, "o-1")), Append.of(stream(billing, "b-1"))))
        }.message.assert().isEqualTo("A group commit appends streams of orders.")
        log.head(0).assert().isNull()
    }

    private fun stream(type: AggregateType, id: String) = EventStreamRecord(
        ref = type.ref("tenant", id),
        version = 1,
        commandId = "command-$id",
        requestId = "request-$id",
        commandType = "${type.context}.${type.name}.Act",
        commitTime = 1_700_000_000_000L,
        events = listOf(EventRecord("${type.context}.${type.name}.Acted", 1, "{}")),
        scope = Scope("owner", "space"),
    )
}
