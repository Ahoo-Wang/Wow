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

class CommandCommittedTest {
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

    private fun committed(): CommandCommitted<ReserveStock> = CommandCommitted(
        commandId = "cmd-2",
        commandType = "inventory.stock.ReserveStock",
        ref = stock,
        version = 3,
        result = result(),
        causation = Causation("cmd-1", "$order@1"),
        executionId = ExecutionId("OrderSaga.onOrderCreated", order, 1),
    )

    @Test
    fun `a receipt names the command, the aggregate it was committed on and its result`() {
        val committed = committed()
        committed.aggregateId.assert().isEqualTo("s-1")
        committed.version.assert().isEqualTo(3)
        committed.result.duplicate.assert().isFalse()
        committed.executionId.subscription.assert().isEqualTo("OrderSaga.onOrderCreated")
    }

    @Test
    fun `a receipt names its command, its type, a version and a result that succeeded`() {
        assertThrownBy<IllegalArgumentException> { committed().copy(commandId = " ") }
        assertThrownBy<IllegalArgumentException> { committed().copy(commandType = "") }
        assertThrownBy<IllegalArgumentException> { committed().copy(version = -1) }
        assertThrownBy<IllegalArgumentException> {
            committed().copy(result = result(CommandResult.Status.REJECTED))
        }
    }
}
