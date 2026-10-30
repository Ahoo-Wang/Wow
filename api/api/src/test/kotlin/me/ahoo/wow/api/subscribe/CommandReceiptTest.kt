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

package me.ahoo.wow.api.subscribe

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import org.junit.jupiter.api.Test

/** The receipts of a command a saga derived (decision D372 S16): one sealed type, rejected or committed. */
class CommandReceiptTest {
    private val order = AggregateType("shop", "order").ref("tenant", "o-1")
    private val stock = AggregateType("inventory", "stock").ref("tenant", "s-1")

    private data class ReserveStock(val quantity: Int)

    private fun result(status: CommandResult.Status = CommandResult.Status.SUCCEEDED) = CommandResult(
        commandId = "cmd-2",
        requestId = "cmd-2",
        correlationId = "cmd-1",
        ref = stock,
        hop = Hop.committed("inventory"),
        function = FunctionInfo(FunctionKind.COMMAND, "inventory", "stock", "ReserveStock"),
        status = status,
        version = 3,
        error = if (status == CommandResult.Status.SUCCEEDED) null else ErrorInfo("stock.insufficient", "No stock"),
        signalTime = 1L,
    )

    private fun rejected(): CommandReceipt.Rejected<ReserveStock> = CommandReceipt.Rejected(
        commandId = "cmd-2",
        commandType = "inventory.stock.ReserveStock",
        ref = stock,
        error = ErrorInfo("stock.insufficient", "Not enough stock"),
        causation = Causation("cmd-1", "$order@1"),
        executionId = ExecutionId("OrderSaga.onOrderCreated", order, 1),
    )

    private fun committed(): CommandReceipt.Committed<ReserveStock> = CommandReceipt.Committed(
        commandId = "cmd-2",
        commandType = "inventory.stock.ReserveStock",
        ref = stock,
        version = 3,
        result = result(),
        causation = Causation("cmd-1", "$order@1"),
        executionId = ExecutionId("OrderSaga.onOrderCreated", order, 1),
    )

    /** What a handler of both receipts makes of one: the fields they share, and each one's own. */
    private fun outcomeOf(receipt: CommandReceipt<ReserveStock>): String = when (receipt) {
        is CommandReceipt.Rejected -> "${receipt.commandId} rejected at ${receipt.version}: ${receipt.error.code}"
        is CommandReceipt.Committed -> "${receipt.commandId} committed at ${receipt.version}"
    }

    @Test
    fun `a rejection names the command, its outcome and the execution that derived it`() {
        val rejected = rejected()
        rejected.ref.assert().isEqualTo(stock)
        rejected.error.code.assert().isEqualTo("stock.insufficient")
        rejected.causation.correlationId.assert().isEqualTo("cmd-1")
        rejected.executionId.toString().assert().isEqualTo("OrderSaga.onOrderCreated:shop.order/tenant/o-1@1")
        rejected.source.assert().isEqualTo(order)
        // A command refused before any decision answers no version.
        rejected.version.assert().isNull()
    }

    @Test
    fun `a commit names the command, the aggregate it was committed on and its result`() {
        val committed = committed()
        committed.ref.id.assert().isEqualTo("s-1")
        committed.version.assert().isEqualTo(3)
        committed.result.duplicate.assert().isFalse()
        committed.executionId.subscription.assert().isEqualTo("OrderSaga.onOrderCreated")
        committed.source.assert().isEqualTo(order)
    }

    @Test
    fun `a handler of both receipts tells them apart and reads what they share`() {
        outcomeOf(rejected()).assert().isEqualTo("cmd-2 rejected at null: stock.insufficient")
        outcomeOf(rejected().copyWith(version = 2)).assert().isEqualTo("cmd-2 rejected at 2: stock.insufficient")
        outcomeOf(committed()).assert().isEqualTo("cmd-2 committed at 3")
    }

    @Test
    fun `a receipt names its command and its type, a commit a version and a result that succeeded`() {
        assertThrownBy<IllegalArgumentException> { rejected().copyWith(commandId = " ") }
        assertThrownBy<IllegalArgumentException> { rejected().copyWith(commandType = "") }
        assertThrownBy<IllegalArgumentException> { committed().copyWith(commandId = " ") }
        assertThrownBy<IllegalArgumentException> { committed().copyWith(commandType = "") }
        assertThrownBy<IllegalArgumentException> { committed().copyWith(version = -1) }
        assertThrownBy<IllegalArgumentException> {
            committed().copyWith(result = result(CommandResult.Status.REJECTED))
        }
    }
}
