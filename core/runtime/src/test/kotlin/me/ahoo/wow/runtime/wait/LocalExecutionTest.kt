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

package me.ahoo.wow.runtime.wait

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import java.time.Clock

/**
 * A duplicate request a node runs itself reaches its HANDLED hop at once only when that hop ends the chain, is of the
 * command's own context, and its subscription is known to be past the stream: otherwise its wait stops at COMMITTED.
 */
class LocalExecutionTest {
    data class Place(val amount: Int)

    private val model = aggregate<Any>("shop", "order") {
        initial { Any() }
        decide<Place>(Creation.ALLOWED) { _, _ -> Decision.NoOp }
    }
    private val command: Command<Any> = Command("c-2", "r-1", model.type.ref("t", "o-1"), Place(1))
    private val stream = EventStreamRecord(
        command.ref,
        1,
        "c-1",
        "r-1",
        "shop.order.Place",
        0L,
        listOf(EventRecord("shop.order.Placed", 1, "{}")),
        Scope.NONE,
    )
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val executor = mockk<Executor> {
        coEvery { execute(any(), any(), any()) } returns CommandOutcome.AlreadyCommitted(stream)
    }

    private fun execution(kind: FunctionKind? = FunctionKind.SYSTEM): LocalExecution {
        val directory = mockk<SubscriptionDirectory> {
            coEvery { hasHandled(any(), any()) } returns true
            every { kindOf(any(), any()) } returns kind
        }
        return LocalExecution(executor, directory, Observer.NONE, Clock.systemUTC()) { origin }
    }

    private fun waitFor(vararg hops: Hop) = WaitContext(
        "w-1",
        origin,
        listOf(Hop.committed("shop")) + hops,
        Long.MAX_VALUE
    )

    @Test
    fun `a duplicate reaches its last hop at once when its subscription is past the stream`(): Unit = runBlocking {
        val settlement = execution().execute(model, command, waitFor(Hop.handled("wow.snapshot", "shop")))
        settlement.reached.single().duplicate.assert().isTrue()
    }

    @Test
    fun `a duplicate stops at COMMITTED when its handled hop is not the last, or of another context, or unknown`(): Unit =
        runBlocking {
            val notLast = waitFor(Hop.handled("order.saga", "shop"), Hop.committed("stock"))
            execution().execute(model, command, notLast).reached.assert().isEmpty()
            val elsewhere = waitFor(Hop.handled("wow.snapshot", "stock"))
            execution().execute(model, command, elsewhere).reached.assert().isEmpty()
            val unknown = waitFor(Hop.handled("wow.snapshot", "shop"))
            execution(kind = null).execute(model, command, unknown).reached.assert().isEmpty()
        }
}
