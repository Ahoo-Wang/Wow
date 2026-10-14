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

package me.ahoo.wow.runtime.log

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemorySnapshotStore
import me.ahoo.wow.runtime.snapshot.SnapshotStoreRouter
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The log and the snapshots of a context whose aggregates are in two groups (decision D177). */
class LogGroupRouterTest {
    private val orders = AggregateType("sales", "order")
    private val invoices = AggregateType("sales", "invoice")
    private val default = InMemoryEventLog(PARTITIONS)
    private val grouped = InMemoryEventLog(PARTITIONS)
    private val router = LogGroupRouter(default, mapOf(invoices to grouped))

    private fun stream(ref: AggregateRef, requestId: String = "${ref.id}-1", commitTime: Long = 1L) = EventStreamRecord(
        ref = ref,
        version = 1,
        commandId = "command-$requestId",
        requestId = requestId,
        commandType = "${ref.type}.Act",
        commitTime = commitTime,
        events = listOf(EventRecord("${ref.type}.Acted", 1, "{}")),
        scope = Scope.NONE,
    )

    @Test
    fun `each aggregate is appended to and read from its group's log`(): Unit = runBlocking {
        val order = orders.ref("t", "o-1")
        val invoice = invoices.ref("t", "i-1")
        router.append(stream(order), null).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        router.append(stream(invoice), null).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        default.read(invoice).toList().assert().isEmpty()
        grouped.read(order).toList().assert().isEmpty()
        router.read(invoice).toList().assert().hasSize(1)
        router.readEntries(order).toList().assert().hasSize(1)
        router.positionOf(invoice, 1)?.sequence.assert().isEqualTo(1L)
        router.findByRequestId(invoices, "t", "i-1-1")?.ref.assert().isEqualTo(invoice)
        router.findByRequestId(orders, "t", "i-1-1").assert().isNull()
        router.logOf(invoices).assert().isSameAs(grouped)
        router.logOf(orders).assert().isSameAs(default)
        router.partitions.assert().isEqualTo(PARTITIONS)
    }

    @Test
    fun `an append of several groups' streams returns the outcomes in the streams' order`(): Unit = runBlocking {
        grouped.append(stream(invoices.ref("t", "i-2")))
        val streams = listOf(
            stream(invoices.ref("t", "i-1")),
            stream(orders.ref("t", "o-1")),
            stream(invoices.ref("t", "i-2")),
        )
        val outcomes = router.appendAll(streams.map { it to null })
        outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        outcomes[1].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        (outcomes[2] as AppendOutcome.AlreadyCommitted).existing.ref.assert().isEqualTo(invoices.ref("t", "i-2"))
        default.read(orders.ref("t", "o-1")).toList().assert().hasSize(1)
        router.appendAll(listOf(stream(orders.ref("t", "o-2")) to null)).single().assert()
            .isInstanceOf(AppendOutcome.Committed::class.java)
        grouped.read(invoices.ref("t", "i-2")).toList().assert().hasSize(1)
    }

    @Test
    fun `a request of any type is the earliest committed among the groups`(): Unit = runBlocking {
        router.append(stream(invoices.ref("t", "i-1"), requestId = "shared", commitTime = 5), null)
        router.append(stream(orders.ref("t", "o-1"), requestId = "shared", commitTime = 7), null)
        router.findByRequestId("t", "shared")?.ref.assert().isEqualTo(invoices.ref("t", "i-1"))
        router.findByRequestId("t", "missing").assert().isNull()
    }

    @Test
    fun `a partition is read in each group's log, not here`(): Unit = runBlocking {
        assertThrows<UnsupportedOperationException> { router.tail(0, null) }
        assertThrows<UnsupportedOperationException> { router.head(0) }
    }

    @Test
    fun `the groups have the context's partitions, and one group is its own log`() {
        assertThrows<IllegalArgumentException> { LogGroupRouter(default, mapOf(invoices to InMemoryEventLog(1))) }
        LogGroupRouter.of(default, emptyMap()).assert().isSameAs(default)
        LogGroupRouter.of(default, mapOf(invoices to grouped)).assert().isInstanceOf(LogGroupRouter::class.java)
    }

    @Test
    fun `each aggregate's snapshot is in its type's store`(): Unit = runBlocking {
        val fallback = InMemorySnapshotStore()
        val own = InMemorySnapshotStore()
        val snapshots = SnapshotStoreRouter.of(fallback, mapOf(invoices to own))
        val invoice = SnapshotRecord(invoices.ref("t", "i-1"), 1, "fp", "{}", firstEventTime = 1, lastEventTime = 1)
        val order = SnapshotRecord(orders.ref("t", "o-1"), 1, "fp", "{}", firstEventTime = 1, lastEventTime = 1)
        snapshots.save(invoice).assert().isTrue()
        snapshots.save(order).assert().isTrue()
        own.load(invoice.ref).assert().isEqualTo(invoice)
        fallback.load(invoice.ref).assert().isNull()
        snapshots.load(order.ref).assert().isEqualTo(order)
        SnapshotStoreRouter.of(fallback, emptyMap()).assert().isSameAs(fallback)
    }

    private companion object {
        const val PARTITIONS = 2
    }
}
