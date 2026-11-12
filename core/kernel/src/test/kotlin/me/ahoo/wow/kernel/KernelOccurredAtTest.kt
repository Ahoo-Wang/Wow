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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

/** D422: a creation command that declares when it occurred (`@OccurredAt`) creates its aggregate at that time. */
class KernelOccurredAtTest {
    data class ImportOrder(val source: String, val placedAt: Long?)
    data class PlaceOrder(val source: String)
    data class Ship(val by: String)
    data class OrderImported(val source: String, val decidedAt: Long)
    data class OrderPlaced(val source: String)
    data class Shipped(val by: String, val decidedAt: Long)

    class OrderState {
        var source = ""
    }

    private val model = aggregate<OrderState>("shop", "order") {
        initial { OrderState() }
        decide<ImportOrder>(
            Creation.REQUIRED
        ) { _, command -> Decision.events(OrderImported(command.source, now)) }
        occurredAt<ImportOrder> { it.placedAt }
        decide<Ship> { _, command -> Decision.events(Shipped(command.by, now)) }
        evolve<OrderImported> { source = it.source }
        evolve<Shipped> { }
    }
    private val ref = model.type.ref("tenant", "legacy-1")
    private val codec = EventCodec()
    private var sequence = 0

    private fun command(body: Any) = Command(
        id = "command-${++sequence}",
        requestId = "request-$sequence",
        ref = ref,
        body = body
    )

    private fun EventStreamRecord.decoded(): List<Any> = events.map { codec.decode(model, it) }

    @Test
    fun `a declared creation commits at its time, written at the clock's, and the next commit at the clock's`() =
        runTest {
            val log = InMemoryEventLog()
            val kernel = Kernel(log, clock = { NOW })
            val imported = kernel.execute(model, command(ImportOrder("legacy-1", PLACED))) as CommandOutcome.Committed
            imported.stream.commitTime.assert().isEqualTo(PLACED)
            // The decide function reads the declared time as its `now`.
            imported.stream.decoded().assert().containsExactly(OrderImported("legacy-1", PLACED))
            imported.stream.headers[EventStreamRecord.WRITTEN_AT].assert().isEqualTo(NOW.toString())
            imported.stream.writtenAt.assert().isEqualTo(NOW)
            imported.first!!.eventTime.assert().isEqualTo(PLACED)
            val shipped = kernel.execute(model, command(Ship("courier"))) as CommandOutcome.Committed
            shipped.stream.commitTime.assert().isEqualTo(NOW)
            shipped.stream.writtenAt.assert().isEqualTo(NOW)
            shipped.stream.headers.assert().doesNotContainKey(EventStreamRecord.WRITTEN_AT)
            shipped.stream.decoded().assert().containsExactly(Shipped("courier", NOW))
            log.read(ref).toList().map { it.commitTime }.assert().containsExactly(PLACED, NOW)
        }

    @Test
    fun `a creation that declares no time commits at the clock's, with no written-at header`() = runTest {
        val kernel = Kernel(InMemoryEventLog(), clock = { NOW })
        val placed = kernel.execute(model, command(ImportOrder("legacy-1", null))) as CommandOutcome.Committed
        placed.stream.commitTime.assert().isEqualTo(NOW)
        placed.stream.headers.assert().doesNotContainKey(EventStreamRecord.WRITTEN_AT)
        placed.stream.decoded().assert().containsExactly(OrderImported("legacy-1", NOW))
    }

    @Test
    fun `a declared time the clock has not reached, or before the epoch, is invalid and writes nothing`() = runTest {
        val log = InMemoryEventLog()
        val kernel = Kernel(log, clock = { NOW })
        val future = kernel.execute(model, command(ImportOrder("legacy-1", NOW + 1))) as CommandOutcome.Rejected
        future.rejection.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
        future.rejection.message.assert().isEqualTo(
            "ImportOrder declares it occurred at 2023-11-14T22:13:20.001Z (${NOW + 1}), after now " +
                "(2023-11-14T22:13:20Z): a time the node's clock has not reached.",
        )
        val negative = kernel.execute(model, command(ImportOrder("legacy-1", -1))) as CommandOutcome.Rejected
        negative.rejection.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
        negative.rejection.message.assert().isEqualTo(
            "ImportOrder declares it occurred at -1, which is no epoch millisecond."
        )
        log.read(ref).toList().assert().isEmpty()
        // The clock's own time is no later than itself.
        kernel.execute(
            model,
            command(ImportOrder("legacy-1", NOW))
        ).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        // An aggregate that exists refuses the creation before its time is read.
        (kernel.execute(model, command(ImportOrder("legacy-1", PLACED))) as CommandOutcome.Rejected).rejection.code
            .assert().isEqualTo(FrameworkCodes.AGGREGATE_EXISTS)
    }

    @Test
    fun `a batch decides a declaring creation alone, at its time, and its next command at the clock's`() = runTest {
        val log = InMemoryEventLog()
        val kernel = Kernel(log, clock = { NOW }, options = KernelOptions(recentRequests = 256))
        val first = kernel.executeBatch(
            model,
            listOf(QueuedCommand(command(ImportOrder("legacy-1", PLACED))), QueuedCommand(command(Ship("courier")))),
        )
        val imported = (first[0] as BatchOutcome.Done).outcome as CommandOutcome.Committed
        imported.stream.commitTime.assert().isEqualTo(PLACED)
        imported.stream.headers[EventStreamRecord.WRITTEN_AT].assert().isEqualTo(NOW.toString())
        // Decided alone, the creation ends the batch: the next command runs on its own.
        first[1].assert().isEqualTo(BatchOutcome.Again)
        val again = kernel.executeBatch(model, listOf(QueuedCommand(command(Ship("courier")))))
        val shipped = (again.single() as BatchOutcome.Done).outcome as CommandOutcome.Committed
        shipped.stream.commitTime.assert().isEqualTo(NOW)
        shipped.stream.headers.assert().doesNotContainKey(EventStreamRecord.WRITTEN_AT)
        log.read(ref).toList().map { it.commitTime }.assert().containsExactly(PLACED, NOW)
    }

    @Test
    fun `only a command that creates its aggregate declares when it occurred, read once`() {
        assertThrownBy<IllegalArgumentException> {
            aggregate<OrderState>("shop", "order") {
                initial { OrderState() }
                decide<Ship> { _, command -> Decision.events(Shipped(command.by, now)) }
                occurredAt<Ship> { 0L }
                evolve<Shipped> { }
            }
        }.hasMessageContaining("the command is NONE: only a command that creates its aggregate (REQUIRED) declares")
        assertThrownBy<IllegalArgumentException> {
            aggregate<OrderState>("shop", "order") {
                initial { OrderState() }
                decide<ImportOrder>(
                    Creation.ALLOWED
                ) { _, command -> Decision.events(OrderImported(command.source, now)) }
                occurredAt<ImportOrder> { it.placedAt }
                evolve<OrderImported> { }
            }
        }.hasMessageContaining("the command is ALLOWED")
        assertThrownBy<IllegalArgumentException> {
            aggregate<OrderState>("shop", "order") {
                initial { OrderState() }
                decide<ImportOrder>(
                    Creation.REQUIRED
                ) { _, command -> Decision.events(OrderImported(command.source, now)) }
                occurredAt<PlaceOrder> { 0L }
                evolve<OrderImported> { }
            }
        }.hasMessageContaining("decides no such command")
        assertThrownBy<IllegalArgumentException> {
            aggregate<OrderState>("shop", "order") {
                initial { OrderState() }
                decide<ImportOrder>(
                    Creation.REQUIRED
                ) { _, command -> Decision.events(OrderImported(command.source, now)) }
                occurredAt<ImportOrder> { it.placedAt }
                occurredAt<ImportOrder> { it.placedAt }
                evolve<OrderImported> { }
            }
        }.hasMessage("shop.order reads when ${ImportOrder::class.java.name} occurred twice.")
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val PLACED = 1_500_000_000_000L
    }
}
