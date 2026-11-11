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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.SetSchedule
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

/**
 * A derived request's identity is one-to-one with what it asks (issue #5560, decision D407): two different targets,
 * command types, business keys or executions never share an id, whatever characters their names hold.
 */
class DerivedIdentityTest {
    class Charge
    class Refund

    private val type = AggregateType("billing", "invoice")
    private val execution = ExecutionId("billing.saga.onPaid", AggregateType("billing", "payment").ref("t", "p-1"), 3)

    /** Every string of up to [length] tokens, the separators, the digits and the length prefix's ':' among them. */
    private fun strings(length: Int): List<String> =
        (1..length).fold(listOf("")) { shorter, _ -> shorter + shorter.flatMap { s -> TOKENS.map { s + it } } }
            .distinct()

    @Test
    fun `distinct lists of fields are encoded distinctly, whatever characters the fields hold`() {
        val fields = strings(2)
        val tuples = fields.flatMap { a -> fields.flatMap { b -> listOf(listOf(a, b), listOf(a, b, "")) } } +
            fields.map { listOf(it) }
        val encoded = tuples.map { DerivedRequests.encoded(*it.toTypedArray()) }
        encoded.toSet().size.assert().isEqualTo(tuples.toSet().size)
    }

    @Test
    fun `distinct targets and command types are distinct keys, the separators in their names included`() {
        val names = strings(2).filter { it.isNotBlank() }
        val targets = names.flatMap { tenant -> names.map { id -> type.ref(tenant, id) } } + type.unassigned("t")
        val keys = targets.flatMap { ref -> listOf(Charge::class.java, Refund::class.java).map { ref to it } }
        keys.map { (ref, command) -> DerivedRequests.keyOf(ref, command) }.toSet().size.assert().isEqualTo(keys.size)
    }

    @Test
    fun `an unassigned target and the target whose id reads unassigned derive their own ids, in either order`() {
        val unassigned = type.unassigned("t")
        val named = type.ref("t", "unassigned")
        named.isAssigned.assert().isTrue()
        val first = DerivedRequests().let {
            listOf(
                it.next(unassigned, Charge::class.java),
                it.next(named, Charge::class.java)
            )
        }
        val again = DerivedRequests().let {
            listOf(
                it.next(named, Charge::class.java),
                it.next(unassigned, Charge::class.java)
            )
        }
        // Each target's first request, whichever the handler built first.
        first.assert().isEqualTo(again.reversed())
        ExecutionSagaContext.derivedId(execution, unassigned, Charge::class.java).assert()
            .isNotEqualTo(ExecutionSagaContext.derivedId(execution, named, Charge::class.java))
        // So with one business key.
        ExecutionSagaContext.derivedId(execution, unassigned, Charge::class.java, "invoice-1").assert()
            .isNotEqualTo(ExecutionSagaContext.derivedId(execution, named, Charge::class.java, "invoice-1"))
    }

    @Test
    fun `a separator moved between tenant, id and business key changes the id`() {
        fun id(ref: AggregateRef, key: String) = ExecutionSagaContext.derivedId(execution, ref, Charge::class.java, key)
        id(type.ref("a/b", "c"), "k").assert().isNotEqualTo(id(type.ref("a", "b/c"), "k"))
        id(type.ref("t", "x#y"), "k").assert().isNotEqualTo(id(type.ref("t", "x"), "y#k"))
        id(type.ref("t", "1:a"), "k").assert().isNotEqualTo(id(type.ref("t", "1"), ":a#k"))
        // The execution's own names too: a commit of another aggregate never derives this one's ids.
        val shifted = ExecutionId(execution.subscription, AggregateType("billing", "payment").ref("t/p", "1"), 3)
        ExecutionSagaContext.derivedId(shifted, type.ref("t", "c"), Charge::class.java).assert()
            .isNotEqualTo(
                ExecutionSagaContext.derivedId(
                    ExecutionId(execution.subscription, AggregateType("billing", "payment").ref("t", "p/1"), 3),
                    type.ref("t", "c"),
                    Charge::class.java,
                ),
            )
    }

    @Test
    fun `a saga's derived id and a compensation's never meet, nor a business key and an occurrence`() {
        val ref = type.ref("t", "c")
        ExecutionSagaContext.derivedId(execution, ref, Charge::class.java).assert()
            .isNotEqualTo(ExecutionReceiptContext.compensationId(execution, "", ref, Charge::class.java))
        ExecutionSagaContext.derivedId(execution, ref, Charge::class.java, 0).assert()
            .isNotEqualTo(ExecutionSagaContext.derivedId(execution, ref, Charge::class.java, "0"))
    }

    @Test
    fun `a saga's schedules and cancels are told apart by their key, whatever order the handler makes them in`() {
        val target = type.ref("t", "c")
        val event = StateEvent(
            execution.ref,
            execution.version,
            "c-3",
            "r-3",
            "billing.payment.Pay",
            1L,
            listOf("paid"),
            "state",
        )

        /** The id of each schedule command one execution records, by its kind and key; a key with `-` cancels. */
        fun handled(order: List<String>): Map<String, String> {
            val context = contextOf(FunctionKind.SAGA, execution.subscription, event) as ExecutionSagaContext
            order.forEach { key ->
                if (key.startsWith("-")) {
                    context.cancel(key.drop(1), target)
                } else {
                    context.schedule(key, ScheduleRule.after(1.hours), CommandRequest.to(target, Charge()))
                }
            }
            return context.commandsOf(emptyList()).associate { command ->
                when (val body = command.body) {
                    is SetSchedule -> "set ${body.key}"
                    is CancelSchedule -> "cancel ${body.key}"
                    else -> error("Not a schedule command: $body.")
                } to command.id
            }
        }

        val first = handled(listOf("a", "b", "-c", "-d"))
        // Its retry reads in another order and makes them in another: each key keeps its id.
        handled(listOf("-d", "b", "-c", "a")).assert().isEqualTo(first)
        first.values.toSet().assert().hasSize(4)
        first["set a"].assert()
            .isEqualTo(ExecutionSagaContext.scheduleId(execution, target, SetSchedule::class.java, "a"))
        first["cancel d"].assert()
            .isEqualTo(ExecutionSagaContext.scheduleId(execution, target, CancelSchedule::class.java, "d"))
    }

    private companion object {
        val TOKENS = listOf("a", "/", "#", ":", "0", "1", "!", "@")
    }
}
