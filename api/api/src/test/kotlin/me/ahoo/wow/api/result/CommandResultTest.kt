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

package me.ahoo.wow.api.result

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test

class CommandResultTest {
    private val saga = Hop.handled("payment.saga", "shop")

    private fun result(chainEnded: Boolean = false, pending: List<Hop> = emptyList()) = CommandResult(
        commandId = "c-1",
        requestId = "r-1",
        correlationId = "c-1",
        ref = AggregateType("shop", "order").ref("t", "o-1"),
        hop = saga,
        function = FunctionInfo(FunctionKind.SAGA, "shop", "payment", "saga"),
        status = CommandResult.Status.SUCCEEDED,
        signalTime = 1L,
        chainEnded = chainEnded,
        pending = pending,
    )

    @Test
    fun `a result reaches its plan by default, with no pending hops`() {
        val result = result()
        result.chainEnded.assert().isFalse()
        result.pending.assert().isEmpty()
    }

    @Test
    fun `a chain that ended early names the hops it did not reach, and only it does`() {
        val ended = result(chainEnded = true, pending = listOf(Hop.committed("shop")))
        ended.pending.assert().containsExactly(Hop.committed("shop"))
        assertThrownBy<IllegalArgumentException> { result(chainEnded = true) }
        assertThrownBy<IllegalArgumentException> { result(pending = listOf(Hop.committed("shop"))) }
    }
}
