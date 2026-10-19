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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.time.Duration.Companion.minutes

/** Forks copy the specification's runtime instead of replaying its steps (decision D245, review finding X-17). */
class AggregateForkTest {
    data class Open(val handle: String)
    data class Deposit(val amount: Int)
    data class Release(val handle: String)
    data class Close(val reason: String)
    data class Opened(val handle: String, val number: String)
    data class Deposited(val amount: Int, val fee: Int, val receipt: Int)
    data class Released(val handle: String)
    data class Closed(val reason: String)

    class Account(val id: String) {
        var balance = 0
        var closed = false
    }

    /** A domain service the decide functions call: a test mocks it. */
    fun interface Fees {
        fun of(amount: Int): Int
    }

    /** A stateful domain service: each call answers the next receipt number. */
    class Receipts {
        var issued = 0

        fun next(): Int = ++issued
    }

    private fun model(fees: Fees, receipts: Receipts = Receipts()) = aggregate<Account>("bank", "account") {
        initial { Account(it.id) }
        decideSuspending<Open>(Creation.REQUIRED) { _, body ->
            if (!reserve("handle", body.handle)) {
                Decision.reject("account.handle-taken", body.handle)
            } else {
                schedule("dormant", ScheduleRule.after(30.minutes), Close("dormant"))
                Decision.events(Opened(body.handle, ids.next()))
            }
        }
        decide<Deposit> { _, body ->
            cancelSchedule("dormant")
            Decision.events(Deposited(body.amount, fees.of(body.amount), receipts.next()))
        }
        decideSuspending<Release> { _, body ->
            release("handle", body.handle)
            Decision.events(Released(body.handle))
        }
        decide<Close> { state, body -> if (state.closed) Decision.NoOp else Decision.events(Closed(body.reason)) }
        evolve<Opened> { }
        evolve<Deposited> { balance += it.amount - it.fee }
        evolve<Released> { }
        evolve<Closed> { closed = true }
    }

    @Test
    fun `a domain service called before a fork is not called again by the forks`() {
        val fees = mockk<Fees>()
        every { fees.of(any()) } returns 1
        aggregateSpec(model(fees)) {
            whenCommand(Open("ahoo")) { expectEventTypes(Opened::class) }
            whenCommand(Deposit(10)) { expectState { balance.assert().isEqualTo(9) } }
            fork("first") { whenCommand(Close("done")) { expectEventTypes(Closed::class) } }
            fork("second") { whenCommand(Close("done")) { expectEventTypes(Closed::class) } }
        }
        verify(exactly = 1) { fees.of(10) }
    }

    @Test
    fun `a stateful domain service is not called again for the prefix`() {
        val receipts = Receipts()
        aggregateSpec(model({ 0 }, receipts)) {
            whenCommand(Open("ahoo")) { }
            whenCommand(Deposit(10)) { expectEvent<Deposited> { receipt.assert().isEqualTo(1) } }
            ref("deposited")
            fork("first") { whenCommand(Deposit(1)) { expectEvent<Deposited> { receipt.assert().isEqualTo(2) } } }
            fork("second") { whenCommand(Deposit(1)) { expectEvent<Deposited> { receipt.assert().isEqualTo(3) } } }
            whenCommand(Deposit(1)) { expectEvent<Deposited> { receipt.assert().isEqualTo(4) } }
            fork(from = "deposited") {
                whenCommand(Deposit(1)) {
                    expectEvent<Deposited> { receipt.assert().isEqualTo(5) }
                    expectState { balance.assert().isEqualTo(11) }
                }
            }
        }
        receipts.issued.assert().isEqualTo(5)
    }

    @Test
    fun `each fork goes on with its own timers, reservations, ids and time`() = aggregateSpec(model({ 0 })) {
        whenCommand(Open("ahoo")) { expectEvent<Opened> { number.assert().isEqualTo("id-1") } }
        advanceTime(10.minutes)
        fork("dormant") {
            now.assert().isEqualTo(this@aggregateSpec.now)
            timer("dormant").assert().isNotNull()
            advanceTime(20.minutes) { expectEvents(Closed("dormant")) }
            holds("handle", "ahoo").assert().isTrue()
            whenCommand(Release("ahoo")) { }
            holds("handle", "ahoo").assert().isFalse()
        }
        fork("active") {
            whenCommand(Deposit(5)) { }
            expectNothingDue(within = 60.minutes)
            holds("handle", "ahoo").assert().isTrue()
        }
        // Neither fork reached here: the schedule is still due, the handle still held, the time where it was.
        holds("handle", "ahoo").assert().isTrue()
        advanceTime(20.minutes) { expectEvents(Closed("dormant")) }
    }

    @Test
    fun `a fork counts the ids on from where the specification was`() {
        val model = aggregate<Account>("bank", "account") {
            initial { Account(it.id) }
            decide<Open>(Creation.ALLOWED) { _, body -> Decision.events(Opened(body.handle, ids.next())) }
            evolve<Opened> { }
        }
        aggregateSpec(model) {
            whenCommand(Open("a")) { expectEvents(Opened("a", "id-1")) }
            fork { whenCommand(Open("b")) { expectEvents(Opened("b", "id-2")) } }
            fork { whenCommand(Open("c")) { expectEvents(Opened("c", "id-2")) } }
            whenCommand(Open("d")) { expectEvents(Opened("d", "id-2")) }
        }
    }

    @Test
    fun `a given state is copied`() = aggregateSpec(model({ 0 })) {
        givenState(version = 4) { balance = 7 }
        fork {
            whenCommand(Deposit(1), expectedVersion = 4) {
                expectState { balance.assert().isEqualTo(8) }
                expectEventTypes(Deposited::class)
            }
        }
        whenCommand(Deposit(2), expectedVersion = 4) { expectState { balance.assert().isEqualTo(9) } }
    }

    @Test
    fun `the reservations given are copied`() = aggregateSpec(model({ 0 })) {
        givenReservation("handle", "taken")
        fork { whenCommand(Open("taken")) { expectRejected("account.handle-taken") } }
        fork { whenCommand(Open("free")) { expectEventTypes(Opened::class) } }
        whenCommand(Open("taken")) { expectRejected("account.handle-taken") }
    }

    @Test
    fun `a fork in a command's checks starts from what the command left`() {
        val fees = mockk<Fees>()
        every { fees.of(any()) } returns 0
        aggregateSpec(model(fees)) {
            whenCommand(Open("ahoo")) {
                expectEventTypes(Opened::class)
                fork("deposited") {
                    whenCommand(Deposit(3)) {
                        expectState { balance.assert().isEqualTo(3) }
                        fork("closed") { whenCommand(Close("x")) { expectState { balance.assert().isEqualTo(3) } } }
                    }
                }
                fork("closed") { whenCommand(Close("x")) { expectState { balance.assert().isZero() } } }
            }
            whenCommand(Close("y")) { expectEvents(Closed("y")) }
        }
        verify(exactly = 1) { fees.of(3) }
    }

    @Test
    fun `a failure in a fork in a command's checks names it`() {
        assertThrownBy<ForkAssertionError> {
            aggregateSpec(model({ 0 })) {
                whenCommand(Open("ahoo")) {
                    fork("outer") { fork { whenCommand(Close("x")) { expectEventTypes(Deposited::class) } } }
                }
            }
        }.hasMessageStartingWith("In fork \"outer > fork 1\": ")
    }

    @Test
    fun `a tree of tests reports each step and each fork on its own`() {
        val results = aggregateSpecTests(model({ 0 })) {
            whenCommand(Open("ahoo")) {
                expectEventTypes(Opened::class)
                fork("fails") { whenCommand(Close("x")) { expectEventTypes(Deposited::class) } }
                fork("passes") {
                    whenCommand(Close("x")) { expectEventTypes(Closed::class) }
                    fork { whenCommand(Close("y")) { expectUnchanged() } }
                }
            }
            advanceTime(30.minutes) { expectEvents(Closed("wrong")) }
            expectNothingDue(within = 1.minutes)
            whenCommand(Deposit(1)) { expectEventTypes(Deposited::class) }
        }.results()
        results.keys.assert().containsExactly(
            "whenCommand(Open)",
            "fails/whenCommand(Close)",
            "passes/whenCommand(Close)",
            "passes/fork 1/whenCommand(Close)",
            "advanceTime(30m)",
            "expectNothingDue(1m)",
            "whenCommand(Deposit)",
        )
        results.filterValues { it != null }.keys.assert().containsExactly("fails/whenCommand(Close)", "advanceTime(30m)")
        results["fails/whenCommand(Close)"].assert().isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `an exception outside a step ends its fork only`() {
        val results = aggregateSpecTests(model({ 0 })) {
            whenCommand(Open("ahoo")) { }
            fork("broken") {
                fork(from = "nowhere") { }
                whenCommand(Close("never")) { }
            }
            fork("whole") { whenCommand(Close("x")) { expectEventTypes(Closed::class) } }
            givenState(Account(ref.id))
        }.results()
        results.keys.assert().containsExactly("whenCommand(Open)", "broken/Error", "whole/whenCommand(Close)", "Error")
        results["broken/Error"].assert().isInstanceOf(IllegalArgumentException::class.java)
        results["Error"].assert().isInstanceOf(IllegalStateException::class.java)
        results["whole/whenCommand(Close)"].assert().isNull()
    }

    @TestFactory
    fun `a specification as a tree of tests`() = aggregateSpecTests(model({ 1 })) {
        whenCommand(Open("ahoo")) {
            expectEventTypes(Opened::class)
            fork("deposited") { whenCommand(Deposit(2)) { expectState { balance.assert().isEqualTo(1) } } }
            fork("dormant") { advanceTime(30.minutes) { expectEvents(Closed("dormant")) } }
        }
    }
}
