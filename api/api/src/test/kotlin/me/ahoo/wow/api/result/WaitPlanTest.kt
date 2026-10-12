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
import me.ahoo.wow.api.decide.Command
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WaitPlanTest {
    private val ref = AggregateType("sales", "order").ref("tenant", "o-1")

    @Test
    fun `a command waits for the snapshot by default, ten seconds at most`() {
        val command = Command("c-1", "r-1", ref, "body")
        command.wait.assert().isEqualTo(WaitPlan.DEFAULT)
        command.wait.isDefault.assert().isTrue()
        command.wait.hops.assert().containsExactly(Hop(Stage.HANDLED, null, Hop.SNAPSHOT_SUBSCRIPTION))
        command.wait.timeout.assert().isEqualTo(10.seconds)
        WaitPlan.COMMITTED.hops.assert().containsExactly(Hop.committed())
        WaitPlan.ACCEPTED.hops.assert().containsExactly(Hop.accepted())
    }

    @Test
    fun `only the default plan is marked default, and a timeout keeps the mark`() {
        WaitPlan.of(Hop.snapshot()).assert().isEqualTo(WaitPlan.SNAPSHOT)
        WaitPlan.SNAPSHOT.isDefault.assert().isFalse()
        WaitPlan.DEFAULT.assert().isNotEqualTo(WaitPlan.SNAPSHOT)
        WaitPlan.DEFAULT.hops.assert().isEqualTo(WaitPlan.SNAPSHOT.hops)
        val longer = WaitPlan.DEFAULT.within(30.seconds)
        longer.isDefault.assert().isTrue()
        longer.timeout.assert().isEqualTo(30.seconds)
        assertThrownBy<IllegalArgumentException> { WaitPlan(listOf(Hop.committed()), isDefault = true) }
    }

    @Test
    fun `a timeout may be a java time Duration`() {
        val plan = WaitPlan(listOf(Hop.committed()), java.time.Duration.ofMillis(1_500))
        plan.timeout.assert().isEqualTo(1_500.milliseconds)
        plan.within(java.time.Duration.ofSeconds(2)).timeout.assert().isEqualTo(2.seconds)
        WaitPlan.DEFAULT.javaTimeout.assert().isEqualTo(java.time.Duration.ofSeconds(10))
        assertThrownBy<IllegalArgumentException> { WaitPlan.COMMITTED.within(java.time.Duration.ZERO) }
    }

    @Test
    fun `a plan has hops and a positive timeout`() {
        val plan = WaitPlan.of(Hop.committed(), Hop.handled("order.payment-saga", "payment"), Hop.committed("stock"))
        plan.hops.size.assert().isEqualTo(3)
        plan.within(3.seconds).timeout.assert().isEqualTo(3.seconds)
        assertThrownBy<IllegalArgumentException> { WaitPlan(emptyList()) }
        assertThrownBy<IllegalArgumentException> { WaitPlan.COMMITTED.within(Duration.ZERO) }
    }

    @Test
    fun `only a HANDLED hop names a subscription, and it must`() {
        Hop.handled("projector.on-placed", "sales").toString().assert().isEqualTo("handled(sales/projector.on-placed)")
        assertThrownBy<IllegalArgumentException> { Hop(Stage.HANDLED) }
        assertThrownBy<IllegalArgumentException> { Hop(Stage.COMMITTED, subscription = "snapshot") }
        assertThrownBy<IllegalArgumentException> { Hop(Stage.ACCEPTED, context = " ") }
    }

    @Test
    fun `a hop prints as the Wow-Wait header spells it`() {
        Hop.accepted().toString().assert().isEqualTo("accepted")
        Hop.accepted("sales").toString().assert().isEqualTo("accepted(sales)")
        Hop.committed().toString().assert().isEqualTo("committed")
        Hop.committed("sales").toString().assert().isEqualTo("committed(sales)")
        Hop.snapshot().toString().assert().isEqualTo("snapshot")
        Hop.snapshot("sales").toString().assert().isEqualTo("snapshot(sales)")
        Hop.handled("audit").toString().assert().isEqualTo("handled(audit)")
        Hop.handled("order-saga.onPaid", "payment").toString().assert().isEqualTo("handled(payment/order-saga.onPaid)")
    }

    @Test
    fun `a result has an error exactly when it did not succeed`() {
        val function = FunctionInfo(FunctionKind.COMMAND, "sales", "order", "Place")
        val succeeded = CommandResult(
            commandId = "c-1",
            requestId = "r-1",
            correlationId = "c-1",
            ref = ref,
            hop = Hop.committed("sales"),
            function = function,
            status = CommandResult.Status.SUCCEEDED,
            version = 1,
            signalTime = 1L,
        )
        succeeded.format.assert().isEqualTo(CommandResult.FORMAT)
        succeeded.duplicate.assert().isFalse()
        succeeded.derived.assert().isEmpty()
        val rejected = succeeded.copy(
            status = CommandResult.Status.REJECTED,
            error = ErrorInfo("sales.order.closed", "closed", listOf(Violation("items[0]", "min", "too few"))),
        )
        rejected.error!!.violations.single().path.assert().isEqualTo("items[0]")
        assertThrownBy<IllegalArgumentException> { succeeded.copy(status = CommandResult.Status.FAILED) }
        assertThrownBy<IllegalArgumentException> { rejected.copy(status = CommandResult.Status.SUCCEEDED) }
        WaitSignal(waitId = "w-1", result = succeeded).format.assert().isEqualTo(WaitSignal.FORMAT)
        assertThrownBy<IllegalArgumentException> { WaitSignal(waitId = "", result = succeeded) }
        assertThrownBy<IllegalArgumentException> { FunctionInfo(FunctionKind.SAGA, "sales", " ", "x") }
        assertThrownBy<IllegalArgumentException> { ErrorInfo("", "no code") }
    }
}
