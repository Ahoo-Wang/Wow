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

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.toCommandRequest
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.schedule.ScheduleRule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.minutes

/** The [SagaContext] entries that take a command: each sends the command's request (#6573). */
class SagaContextsTest {
    data class SubmitOrder(val item: String)

    data class CancelOrder(val reason: String)

    private val order = AggregateType("shop", "order")

    @Test
    fun `call of a command should call the command's request`() {
        val context = mockk<SagaContext>()
        val called = mutableListOf<CommandRequest<*>>()
        val result = mockk<CommandResult>()
        coEvery { context.call(capture(called)) } returns result
        runBlocking {
            context.call(SubmitOrder("a")).assert().isSameAs(result)
            context.call(SubmitOrder("b"), "o-2") { requestId("r-2") }.assert().isSameAs(result)
        }
        called.assert().containsExactly(
            SubmitOrder("a").toCommandRequest(),
            CommandRequest.of(SubmitOrder("b"), "o-2", requestId = "r-2"),
        )
    }

    @Test
    fun `schedule of a command should schedule the command's request under the key and the rule`() {
        val context = mockk<SagaContext>()
        val scheduled = mutableListOf<CommandRequest<*>>()
        val rule = ScheduleRule.after(10.minutes)
        every { context.schedule("deadline", rule, capture(scheduled)) } just Runs
        context.schedule("deadline", rule, CancelOrder("late"), "o-1")
        context.schedule("deadline", rule, CancelOrder("late")) { to(order.ref("t", "o-2")) }
        scheduled.assert().containsExactly(
            CommandRequest.of(CancelOrder("late"), "o-1"),
            CommandRequest.to(order.ref("t", "o-2"), CancelOrder("late")),
        )
    }

    @Test
    fun `a request called with a block should fail before the context is called`() {
        val context = mockk<SagaContext>()
        val request = CommandRequest.of(SubmitOrder("a"), "o-1")
        assertThrows<IllegalArgumentException> { runBlocking { context.call(request) { requestId("r-1") } } }
            .message.assert().contains("pass a request that is built as it is")
    }
}
