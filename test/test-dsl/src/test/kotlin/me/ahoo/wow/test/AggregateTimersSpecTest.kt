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

package me.ahoo.wow.test

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.lifecycle.AggregateDeleted
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.RetryDefaults
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.timer.TimerStatus
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.net.URLClassLoader
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** An aggregate spec's timers fire, settle and are given as a node's do (#6962). */
class AggregateTimersSpecTest {
    data class Submit(val total: Int)
    data class Submitted(val total: Int)
    data class Expire(val reason: String)

    /** What the decision of a scheduled Expire read of its command: its operator, its owner and two headers. */
    data class Expired(val operator: String?, val owner: String?, val trace: String?, val app: String?)

    class Ticket(val id: String) {
        var expired = false
    }

    /** A ticket that expires 30 minutes after its submission, as [expire] decides. */
    private fun ticketModel(
        expire: DecideScope.(Ticket, Expire) -> Decision = { _, _ ->
            Decision.events(
                Expired(
                    command.operator?.id,
                    command.scope.ownerId,
                    command.headers[TRACE],
                    command.headers[RequestFacts.APP_ID],
                ),
            )
        },
    ): AggregateModel<Ticket> = aggregate("shop", "ticket") {
        initial { Ticket(it.id) }
        decide<Submit>(Creation.REQUIRED) { _, body ->
            schedule("expire", ScheduleRule.after(30.minutes), Expire("unpaid"))
            Decision.events(Submitted(body.total))
        }
        decide<Expire> { state, body -> expire(state, body) }
        evolve<Submitted> { }
        evolve<Expired> { expired = true }
    }

    private val buyer = Caller.of(
        tenantId = "acme",
        scope = Scope("alice"),
        subject = "alice",
        attributes = mapOf(Caller.APP to listOf("console")),
    )

    @Test
    fun `a scheduled command should carry the scheduling commit's operator and scope, and only propagated facts`() =
        aggregateSpec(ticketModel(), caller = buyer) {
            // As a node's firer sends it (ContextTimerFirer): with the operator and the scope the timer took from the
            // commit that scheduled it, and of that commit's headers only the propagated facts, the caller's
            // application among them. Moved from review/r1-testkit (DslReviewR1Test).
            whenCommand(Submit(10), headers = mapOf(TRACE to "t-1")) { expectEventTypes(Submitted::class) }
            advanceTime(30.minutes) {
                expectEvents(Expired(operator = "alice", owner = "alice", trace = null, app = "console"))
            }
            // Settled as a node's runner settles a sent timer: done once, with the decision's outcome.
            val fired = requireNotNull(timer("expire"))
            fired.status.assert().isEqualTo(TimerStatus.FIRED)
            fired.attempts.assert().isEqualTo(1)
            fired.outcome.assert().isEqualTo("committed")
        }

    @Test
    fun `a scheduled command whose decision throws should leave its timer pending, to be sent again`() =
        aggregateSpec(ticketModel { _, body -> throw NumberFormatException(body.reason) }) {
            // As a node's runner settles a failed send (TimerRunner.failed) with its default retry (RetryDefaults):
            // PENDING, one more attempt, due after the backoff, and PARKED at the last attempt. Moved from
            // review/r1-testkit (DslReviewR1Test), where the spec settled it FIRED.
            whenCommand(Submit(10)) { expectEventTypes(Submitted::class) }
            advanceTime(30.minutes)
            val first = requireNotNull(timer("expire"))
            first.status.assert().isEqualTo(TimerStatus.PENDING)
            first.attempts.assert().isEqualTo(1)
            first.dueAt.assert().isEqualTo(now.toEpochMilli() + RetryDefaults.TIMER_INITIAL_BACKOFF.inWholeMilliseconds)
            first.outcome.assert().isNotNull()
            // Sent again once due, failing as its decision does.
            advanceTime(RetryDefaults.TIMER_INITIAL_BACKOFF) { expectError<NumberFormatException>() }
            val second = requireNotNull(timer("expire"))
            second.attempts.assert().isEqualTo(2)
            val twice = 2 * RetryDefaults.TIMER_INITIAL_BACKOFF.inWholeMilliseconds
            second.dueAt.assert().isEqualTo(now.toEpochMilli() + twice)
            // Each next wait is longer, at most the longest backoff: one send in each, up to the last attempt.
            repeat(RetryDefaults.TIMER_MAX_ATTEMPTS - 2) { advanceTime(RetryDefaults.TIMER_MAX_BACKOFF) }
            val parked = requireNotNull(timer("expire"))
            parked.status.assert().isEqualTo(TimerStatus.PARKED)
            parked.attempts.assert().isEqualTo(RetryDefaults.TIMER_MAX_ATTEMPTS)
            expectNothingDue(RetryDefaults.TIMER_MAX_BACKOFF)
        }

    data class Plan(val orderId: String)
    data class Planned(val orderId: String)
    data class Remind(val orderId: String)

    class Planner(val id: String)

    private val stock = AggregateType("inventory", "stock")

    /** Plans an order a day ahead: the peer's stock is sent a Reserve, and [another] planner a Remind. */
    private fun plannerModel(another: String? = null): AggregateModel<Planner> = aggregate("shop", "planner") {
        initial { Planner(it.id) }
        decide<Plan>(Creation.REQUIRED) { _, body ->
            val reserve = ClassPathCommandTypesTest.Reserve(body.orderId)
            schedule("restock", ScheduleRule.after(1.days), reserve, stock.ref(command.ref.tenantId, "mug"))
            another?.let {
                val planner = command.ref.type.ref(command.ref.tenantId, it)
                schedule("remind", ScheduleRule.after(1.days), Remind(body.orderId), planner)
            }
            Decision.events(Planned(body.orderId))
        }
        decide<Remind> { _, _ -> Decision.events() }
        evolve<Planned> { }
    }

    /** Runs [block] with the peer contract of the stock (`commands/peer`) on the class path, as its api jar puts it. */
    private fun <T> withPeer(block: () -> T): T {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader =
            URLClassLoader(arrayOf(requireNotNull(javaClass.getResource("/commands/peer/"))), javaClass.classLoader)
        return try {
            block()
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    @Test
    fun `a due command for another context's aggregate should be sent there, deciding nothing here`() = withPeer {
        // As a node's firer sends it to that context's topic, settled with the outcome sent.
        aggregateSpec(plannerModel()) {
            whenCommand(Plan("order-1")) { expectEventTypes(Planned::class) }
            advanceTime(1.days)
            val restock = requireNotNull(timer("restock"))
            restock.status.assert().isEqualTo(TimerStatus.FIRED)
            restock.outcome.assert().isEqualTo("sent")
        }
    }

    @Test
    fun `a due command for another aggregate of the context should be refused by name, a node deciding it there`() {
        withPeer {
            assertThrownBy<IllegalStateException> {
                aggregateSpec(plannerModel(another = "planner-2")) {
                    whenCommand(Plan("order-1")) { expectEventTypes(Planned::class) }
                    advanceTime(1.days)
                }
            }.hasMessageContaining("The timer 'remind' of shop.planner")
                .hasMessageContaining("a specification of one aggregate cannot decide another's command")
        }
    }

    @Test
    fun `a given stream that sets a schedule should keep its timer, as a node's timers subscription does`() {
        // As a node's timers subscription writes a timer from any stream that holds a ScheduleSet, a given one too.
        // Moved from review/r1-testkit (DslReviewR1Test).
        val model = ticketModel()
        val expireType = model.commandTypes.first { it.type == Expire::class.java }.name
        aggregateSpec(model) {
            val body = WowJson.mapper.valueToTree<ObjectNode>(Expire("unpaid"))
            given(Submitted(10), ScheduleSet("expire", ScheduleRule.after(30.minutes), ref, expireType, body))
            timer("expire")?.status.assert().isEqualTo(TimerStatus.PENDING)
            advanceTime(30.minutes) { expectEventTypes(Expired::class) }
            timer("expire")?.status.assert().isEqualTo(TimerStatus.FIRED)
        }
        // A given deletion cancels them, as the subscription cancels every timer of a deleted aggregate.
        aggregateSpec(model) {
            val body = WowJson.mapper.valueToTree<ObjectNode>(Expire("unpaid"))
            given(Submitted(10), ScheduleSet("expire", ScheduleRule.after(30.minutes), ref, expireType, body))
            given(AggregateDeleted())
            timer("expire")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
            expectNothingDue(1.minutes * 31)
        }
    }

    private companion object {
        /** A request fact no command derived from it inherits. */
        const val TRACE = "X-Trace"
    }
}
