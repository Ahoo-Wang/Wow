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

package me.ahoo.wow.migrate

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EventStreamExporterTest {
    private val order = NineXAggregate("example-service", "order")
    private val cart = NineXAggregate("example-service", "cart")
    private val orderType = AggregateType("example", "order")
    private val cartType = AggregateType("example", "cart")
    private val log = InMemoryEventLog(4)
    private val nineX = InMemoryNineXEventSource(NineXExample.streams())
    private val target = InMemoryNineXEventTarget(nineX)
    private val checkpoints = InMemoryCheckpointRepository()
    private val reports = mutableListOf<ExportProgress>()
    private val converter = StreamConverter(NineXExample.plan)

    private fun exporter(options: ExportOptions = ExportOptions(pageSize = 2), into: NineXEventTarget = target) =
        EventStreamExporter(NineXExample.plan, { log }, into, checkpoints, options, { 1_000L }) { reports += it }

    @BeforeEach
    fun imported(): Unit = runTest {
        val importer = EventStreamImporter(nineX, NineXExample.plan, { log }, InMemoryCheckpointRepository())
        importer.importAll(order)
        importer.importAll(cart)
    }

    private val lastOrder: EventStreamRecord
        get() = converter.convert(NineXExample.stream("order-stream-v4.json"))

    /** V10's own streams after the cut-over: the order received (v5), and a new cart of two streams. */
    private suspend fun writtenByV10(): List<EventStreamRecord> {
        val received = lastOrder.copyWith(
            version = 5,
            commandId = "v10-receive",
            requestId = "v10-receive",
            commandType = "example.order.ReceiptOrder",
            commitTime = lastOrder.commitTime + 10,
            events = listOf(EventRecord("example.order.OrderReceived", 1, "{}")),
        )
        val firstCart = converter.convert(NineXExample.stream("cart-stream-v1.json"))
        val cartRef = cartType.ref(firstCart.ref.tenantId, "v10-cart")
        val added = firstCart.copyWith(ref = cartRef, commandId = "v10-add", requestId = "v10-add")
        val changed = converter.convert(NineXExample.stream("cart-stream-v3.json"))
            .copyWith(ref = cartRef, version = 2, commandId = "v10-change", requestId = "v10-change")
        return listOf(received, added, changed).onEach {
            log.append(it).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        }
    }

    @Test
    fun `the streams V10 wrote after the cut-over should go back to 9x, and nothing twice`(): Unit = runTest {
        val written = writtenByV10()
        val scan = exporter().scan(NineXExample.CONTEXT)
        scan.passed.assert().isTrue()
        scan.missing.assert().isEqualTo(3)
        scan.streams.assert().isEqualTo(mapOf(cartType to 6L, orderType to 5L))
        scan.render().assert().contains("example: 11 streams up to the heads, 3 not in 9.x")
            .contains("VERDICT example: roll back by export: 3 streams go back to 9.x")
        scan.closedAt.assert().isNull()

        val progress = exporter().export(scan)
        progress.exported.assert().isEqualTo(3)
        progress.already.assert().isEqualTo(8)
        progress.done.assert().isTrue()
        progress.render().assert().contains("3 streams exported to 9.x, 8 already there")
        target.written.map { converter.convert(it) }.toSet().assert().isEqualTo(written.toSet())
        exporter().verify(scan).all { it.passed }.assert().isTrue()
        exporter().verify(scan).map { it.render() }.assert()
            .contains("EQUAL example-service.order <- example.order: V10 5 streams, 9.x 5 streams")
        checkpoints.load().exportOf(NineXExample.CONTEXT)!!.exported.assert().isEqualTo(3)

        // Again from the checkpoint: nothing left; with --restart: every stream is already there.
        exporter().export(exporter().scan(NineXExample.CONTEXT)).exported.assert().isEqualTo(3)
        target.written.size.assert().isEqualTo(3)
        exporter(ExportOptions(restart = true)).export(exporter().scan(NineXExample.CONTEXT)).also {
            it.exported.assert().isEqualTo(0)
            it.already.assert().isEqualTo(11)
        }
        target.written.size.assert().isEqualTo(3)
    }

    @Test
    fun `a resumed export should write only what V10 appended after its checkpoint`(): Unit = runTest {
        val written = writtenByV10()
        exporter().export(exporter().scan(NineXExample.CONTEXT))
        val shipped = written.first().copyWith(
            version = 6,
            commandId = "v10-again",
            requestId = "v10-again",
            events = listOf(EventRecord("example.order.OrderReceived", 1, "{}")),
        )
        log.append(shipped)
        val again = exporter().export(exporter().scan(NineXExample.CONTEXT))
        again.exported.assert().isEqualTo(4)
        target.written.last().version.assert().isEqualTo(6)
    }

    @Test
    fun `a V10-only type should refuse the export, naming it, before anything is written`(): Unit = runTest {
        log.append(
            lastOrder.copyWith(
                ref = orderType.ref(lastOrder.ref.tenantId, "v10-order"),
                version = 1,
                commandId = "v10-create",
                requestId = "v10-create",
                commandType = "example.order.CreateOrder",
                events = listOf(EventRecord("example.order.OrderCreated", 2, "{}")),
            ),
        )
        val scan = exporter().scan(NineXExample.CONTEXT)
        scan.passed.assert().isFalse()
        scan.problems.assert().isEqualTo(
            mapOf("the V10 event example.order.OrderCreated@2 of example.order has no 9.x event in the map" to 1L),
        )
        scan.render().assert().contains("REFUSED the V10 event example.order.OrderCreated@2")
            .contains("(1 streams, the first committed at ${java.time.Instant.ofEpochMilli(lastOrder.commitTime)})")
        scan.closedAt.assert().isEqualTo(lastOrder.commitTime)
        scan.verdict.assert().isEqualTo(
            "VERDICT example: no rollback by export: the window closed at " +
                "${java.time.Instant.ofEpochMilli(lastOrder.commitTime)}, when V10 first committed a type 9.x cannot read",
        )
        assertThrows<IllegalStateException> { exporter().export(scan) }
        target.written.assert().isEmpty()
    }

    @Test
    fun `a stream 9x holds otherwise should be a conflict`(): Unit = runTest {
        writtenByV10()
        nineX.add(
            NineXExample.stream("order-stream-v4.json").let {
                it.copy(id = "9x-after", version = 5, commandId = "9x-command", requestId = "9x-command")
            },
        )
        val scan = exporter().scan(NineXExample.CONTEXT)
        scan.conflicts.single().assert().contains("v5 is command v10-receive in V10 and command 9x-command")
        scan.render().assert().contains("CONFLICT")
        scan.verdict.assert().isEqualTo("VERDICT example: no rollback by export: 9.x was written after the cut-over")
        scan.passed.assert().isFalse()
    }

    @Test
    fun `9x streams V10 does not hold should make the counts differ`(): Unit = runTest {
        nineX.add(
            NineXExample.stream("cart-stream-v1.json").let {
                it.copy(id = "9x-only", aggregateId = it.aggregateId.copy(id = "9x-cart"))
            }
        )
        val scan = exporter().scan(NineXExample.CONTEXT)
        exporter().export(scan)
        exporter().verify(scan).single { !it.passed }.render().assert()
            .isEqualTo("DIFFERS example-service.cart <- example.cart: V10 4 streams, 9.x 5 streams")
    }

    @Test
    fun `a stream 9x got after the scan should be skipped when it is V10's own, and stop the export otherwise`(): Unit = runTest {
        val written = writtenByV10()
        val scan = exporter().scan(NineXExample.CONTEXT)
        // A run stopped before its checkpoint wrote the received order already.
        target.insert(order, listOf(StreamReverter(NineXExample.plan) { _, _ -> emptyList() }.revert(written.first())))
        exporter().export(scan).also {
            it.exported.assert().isEqualTo(2)
            it.already.assert().isEqualTo(9)
        }

        val other = InMemoryNineXEventTarget(InMemoryNineXEventSource(NineXExample.streams()))
        val racing = object : NineXEventTarget by other {
            override fun insert(aggregate: NineXAggregate, streams: List<NineXStream>): Set<NineXStreamKey> {
                other.insert(aggregate, streams.map { it.copy(id = "${it.id}-9x", commandId = "9x-command") })
                return streams.map { NineXStreamKey(it.aggregateId.id, it.version) }.toSet()
            }
        }
        val fresh = InMemoryCheckpointRepository()
        val exporter = EventStreamExporter(NineXExample.plan, { log }, racing, fresh, ExportOptions())
        assertThrows<MigrationException> { exporter.export(exporter.scan(NineXExample.CONTEXT)) }
            .message.assert().contains("9.x was written since the scan")
    }

    @Test
    fun `a log V10 has not written to since the import should be rolled back by switching back`(): Unit = runTest {
        exporter().scan(NineXExample.CONTEXT).verdict.assert()
            .isEqualTo("VERDICT example: roll back by switching back: 9.x holds every stream V10 holds")
    }

    @Test
    fun `the options should be checked`() {
        assertThrows<IllegalArgumentException> { ExportOptions(pageSize = 0) }
        assertThrows<IllegalArgumentException> { ExportOptions(concurrency = 0) }
    }
}
