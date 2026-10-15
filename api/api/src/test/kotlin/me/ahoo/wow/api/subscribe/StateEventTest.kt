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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.SetSchedule
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

class StateEventTest {
    private val ref = AggregateType("example", "cart").ref("tenant", "c-1")

    private fun stateEvent(causation: Causation? = null) = StateEvent(
        ref = ref,
        version = 3,
        commandId = "cmd-3",
        requestId = "req-3",
        commandType = "example.cart.AddItem",
        commitTime = 42,
        events = listOf("ItemAdded"),
        state = mapOf("items" to 1),
        causation = causation,
    )

    @Test
    fun `a state event is identified by its commit and roots its own chain`() {
        val event = stateEvent()
        event.id.assert().isEqualTo("example.cart/tenant/c-1@3")
        event.correlationId.assert().isEqualTo("cmd-3")
        event.scope.assert().isEqualTo(Scope.NONE)
        event.headers.assert().isEmpty()
    }

    @Test
    fun `a derived commit belongs to the chain of its root command`() {
        stateEvent(Causation("root", "example.order/tenant/o-1@1")).correlationId.assert().isEqualTo("root")
    }

    @Test
    fun `a state event holds a committed stream`() {
        assertThrownBy<IllegalArgumentException> { stateEvent().copyWith(version = 0) }
        assertThrownBy<IllegalArgumentException> { stateEvent().copyWith(commandId = " ") }
        assertThrownBy<IllegalArgumentException> { stateEvent().copyWith(commandType = "") }
        assertThrownBy<IllegalArgumentException> { stateEvent().copyWith(events = emptyList()) }
    }

    @Test
    fun `an execution id is the idempotency key of one handler for one commit`() {
        val id = ExecutionId("cart-projection.onEvent", ref, 3)
        id.toString().assert().isEqualTo("cart-projection.onEvent:example.cart/tenant/c-1@3")
        id.assert().isEqualTo(ExecutionId("cart-projection.onEvent", ref, 3))
        assertThrownBy<IllegalArgumentException> { ExecutionId(" ", ref, 3) }
        assertThrownBy<IllegalArgumentException> { ExecutionId("s", ref, 0) }
    }

    @Test
    fun `contexts give the execution id and a saga derives commands`() {
        val execution = ExecutionId("payment-saga.onEvent", ref, 3)
        val project = object : ProjectContext {
            override val executionId = execution
        }
        val react = object : ReactContext {
            override val executionId = execution
        }
        val saga = object : SagaContext {
            override val executionId = execution
            override val ids = object : Ids {
                override fun next() = "id"

                override fun next(type: AggregateType) = "$type-id"
            }
            private var index = 0

            override fun <C : Any> command(ref: AggregateRef, body: C): Command<C> {
                val id = "$executionId#${index++}"
                return Command(id, id, ref, body, causation = Causation("cmd-3", "$ref@3"))
            }

            override fun schedule(key: String, rule: ScheduleRule, target: AggregateRef, command: Any) =
                command(target, SetSchedule(key, rule, "example.cart.Remind", "{}"))

            override fun cancel(key: String, target: AggregateRef) = command(target, CancelSchedule(key))
        }
        listOf(project, react, saga).map { it.executionId }.distinct().assert().containsExactly(execution)
        saga.command(ref, "Pay").id.assert().isEqualTo("payment-saga.onEvent:example.cart/tenant/c-1@3#0")
        saga.schedule("remind", ScheduleRule.after(1.minutes), ref, "Remind").body.rule.assert()
            .isEqualTo(ScheduleRule.After(60_000))
        saga.cancel("remind", ref).body.assert().isEqualTo(CancelSchedule("remind"))
    }
}
