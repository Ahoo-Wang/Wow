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
import me.ahoo.wow.api.result.ErrorInfo
import org.junit.jupiter.api.Test

class CommandRejectedTest {
    private val order = AggregateType("shop", "order").ref("tenant", "o-1")
    private val stock = AggregateType("inventory", "stock").ref("tenant", "s-1")

    private data class ReserveStock(val quantity: Int)

    private fun rejected(): CommandRejected<ReserveStock> = CommandRejected(
        commandId = "cmd-2",
        commandType = "inventory.stock.ReserveStock",
        ref = stock,
        error = ErrorInfo("stock.insufficient", "Not enough stock"),
        causation = Causation("cmd-1", "$order@1"),
        executionId = ExecutionId("OrderSaga.onOrderCreated", order, 1),
    )

    @Test
    fun `a rejection names the command, its outcome and the execution that derived it`() {
        val rejected = rejected()
        rejected.ref.assert().isEqualTo(stock)
        rejected.error.code.assert().isEqualTo("stock.insufficient")
        rejected.causation.correlationId.assert().isEqualTo("cmd-1")
        rejected.executionId.toString().assert().isEqualTo("OrderSaga.onOrderCreated:shop.order/tenant/o-1@1")
    }

    @Test
    fun `a rejection names its command and the command's type`() {
        assertThrownBy<IllegalArgumentException> { rejected().copy(commandId = " ") }
        assertThrownBy<IllegalArgumentException> { rejected().copy(commandType = "") }
    }
}
