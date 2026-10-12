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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

/** Decision D62: the chains a wait may follow, checked at admission. */
class WaitChainTest {
    private val kinds = mapOf(
        "snapshot" to FunctionKind.SNAPSHOT,
        "order.saga" to FunctionKind.SAGA,
        "order.projector" to FunctionKind.PROJECT,
    )
    private val directory = object : SubscriptionDirectory {
        override fun kindOf(context: String, subscription: String): FunctionKind? =
            if (context == "shop") kinds[subscription] else null

        override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean = false
    }

    private fun check(vararg hops: Hop, directory: SubscriptionDirectory = this.directory): CheckedPlan =
        WaitChain.of(WaitPlan.of(*hops), "shop", directory, 4)

    private fun reason(vararg hops: Hop): String = (check(*hops) as WaitChain.Invalid).reason

    @Test
    fun `a hop without a context is in the command's context`() {
        val chain = check(Hop.committed(), Hop.handled("order.saga"), Hop.committed()) as WaitChain
        chain.hops.assert().containsExactly(
            Hop.committed("shop"),
            Hop.handled("order.saga", "shop"),
            Hop.committed("shop"),
        )
        chain.firstHandled.assert().isEqualTo(Hop.handled("order.saga", "shop"))
        chain.continuesAfter(Hop.handled("order.saga", "shop")).assert().isTrue()
    }

    @Test
    fun `the default waits for COMMITTED where there is no snapshot subscription`() {
        (WaitChain.of(WaitPlan.SNAPSHOT, "shop", directory, 4) as WaitChain).hops
            .assert().containsExactly(Hop.snapshot("shop"))
        (WaitChain.of(WaitPlan.SNAPSHOT, "shop", SubscriptionDirectory.NONE, 4) as WaitChain).hops
            .assert().containsExactly(Hop.committed("shop"))
    }

    @Test
    fun `only a saga's hop may be followed by more hops`() {
        reason(Hop.handled("order.projector"), Hop.committed()).assert().contains("Only a saga's hop")
        reason(Hop.snapshot(), Hop.snapshot()).assert().contains("Only a saga's hop")
    }

    @Test
    fun `stages of one command come in order, each once`() {
        reason(Hop.committed(), Hop.accepted()).assert().contains("cannot follow")
        reason(Hop.committed(), Hop.committed()).assert().contains("cannot follow")
        (check(Hop.accepted(), Hop.committed(), Hop.snapshot()) is WaitChain).assert().isTrue()
        (check(Hop.handled("order.saga"), Hop.accepted(), Hop.snapshot()) is WaitChain).assert().isTrue()
    }

    @Test
    fun `a chain names known subscriptions and has at most four hops`() {
        reason(Hop.handled("missing")).assert().contains("unknown subscription")
        reason(Hop.handled("order.saga", "payment"), Hop.committed()).assert().contains("unknown subscription")
        reason(
            Hop.committed(),
            Hop.handled("order.saga"),
            Hop.handled("order.saga"),
            Hop.handled("order.saga"),
            Hop.committed(),
        ).assert().contains("at most 4 hops")
    }
}
