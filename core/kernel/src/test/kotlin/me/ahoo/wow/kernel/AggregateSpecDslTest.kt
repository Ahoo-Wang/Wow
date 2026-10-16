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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.ReservationTenancy
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.test.ForkAssertionError
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/** The test DSL's additions of P.5 (decision D184): givenState, forks, errors by type, and the G18/G25 gaps. */
class AggregateSpecDslTest {
    data class Open(val limit: Int)
    data class Deposit(val amount: Int)
    data class Withdraw(val amount: Int)
    data class Close(val reason: String)
    data class Opened(val limit: Int)
    data class Deposited(val amount: Int)
    data class Withdrawn(val amount: Int)
    data class Closed(val reason: String)

    class Account(val id: String) {
        var balance = 0
        var limit = 0
        var closed = false
    }

    private val model = aggregate<Account>("bank", "account") {
        initial { Account(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body ->
            schedule("dormant", ScheduleRule.after(30.minutes), Close("dormant"))
            Decision.events(Opened(body.limit))
        }
        decide<Deposit> { state, body ->
            require(body.amount > 0) { "A deposit is positive, not ${body.amount}." }
            if (state.balance + body.amount > state.limit) {
                Decision.reject("account.over-limit", "The limit is ${state.limit}.")
            } else {
                cancelSchedule("dormant")
                Decision.events(Deposited(body.amount))
            }
        }
        decide<Withdraw> { state, body ->
            if (body.amount > state.balance) {
                Decision.reject("account.insufficient", "Only ${state.balance}.")
            } else {
                Decision.events(Withdrawn(body.amount))
            }
        }
        decide<Close> { state, body -> if (state.closed) Decision.NoOp else Decision.events(Closed(body.reason)) }
        evolve<Opened> { limit = it.limit }
        evolve<Deposited> { balance += it.amount }
        evolve<Withdrawn> { balance -= it.amount }
        evolve<Closed> { closed = true }
    }

    @Test
    fun `a given state is where the commands start`() = aggregateSpec(model) {
        givenState(
            Account(ref.id).apply {
                balance = 50
                limit = 100
            },
            version = 7,
        )
        whenCommand(Withdraw(20), expectedVersion = 7) {
            expectEvents(Withdrawn(20))
            outcome.assert().isInstanceOf(CommandOutcome.Committed::class.java)
            (outcome as CommandOutcome.Committed).stream.version.assert().isEqualTo(8)
            expectState { balance.assert().isEqualTo(30) }
        }
        given(Deposited(5))
        whenCommand(Withdraw(35)) {
            expectEventTypes(Withdrawn::class)
            expectState { balance.assert().isZero() }
        }
        whenCommand(Withdraw(1)) {
            expectRejected("account.insufficient") { message.assert().isEqualTo("Only 0.") }
            expectState { balance.assert().isZero() }
        }
    }

    @Test
    fun `a given state built from the initial one keeps its scope`() = aggregateSpec(model) {
        givenState(version = 3, scope = Scope("ahoo", null)) { limit = 10 }
        whenCommand(Close("done"), scope = Scope("bob", null)) { expectRejected(KernelRejections.FORBIDDEN) }
        whenCommand(Close("done"), scope = Scope("ahoo", null)) {
            expectEvent<Closed> { reason.assert().isEqualTo("done") }
            expectScope(Scope("ahoo", null))
        }
        whenCommand(Close("again")) {
            expectUnchanged()
            expectState { closed.assert().isTrue() }
        }
    }

    @Test
    fun `a command is sent in the name of its operator`() = aggregateSpec(model) {
        given(Opened(10))
        whenCommand(Deposit(1), operator = Operator("ahoo", "tenant")) {
            expectEvents(Deposited(1))
            (outcome as CommandOutcome.Committed).stream.operator.assert().isEqualTo(Operator("ahoo", "tenant"))
        }
        whenCommand(Deposit(1)) {
            (outcome as CommandOutcome.Committed).stream.operator.assert().isNull()
        }
    }

    @Test
    fun `the state given changes nothing when the caller changes it later`() = aggregateSpec(model) {
        val account = Account(ref.id).apply { limit = 10 }
        givenState(account)
        account.limit = 0
        whenCommand(Deposit(10)) { expectState { balance.assert().isEqualTo(10) } }
    }

    @Test
    fun `a given state comes first`() {
        assertThrownBy<IllegalStateException> {
            aggregateSpec(model) {
                given(Opened(10))
                givenState(Account(ref.id))
            }
        }
    }

    @Test
    fun `an exception of the decide function is expected by its type`() = aggregateSpec(model) {
        given(Opened(100))
        whenCommand(Deposit(-1)) {
            error.assert().isInstanceOf(IllegalArgumentException::class.java)
            expectError<IllegalArgumentException> { message.assert().isEqualTo("A deposit is positive, not -1.") }
            expectState { balance.assert().isZero() }
        }
        whenCommand(Deposit(1)) {
            expectNoError()
            expectEventTypes(Deposited::class.java)
        }
    }

    @Test
    fun `an exception nobody expects fails the specification`() {
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(model) {
                given(Opened(100))
                whenCommand(Deposit(-1)) { }
            }
        }
    }

    @Test
    fun `an error of another type fails the expectation`() {
        assertThrownBy<AssertionError> {
            aggregateSpec(model) {
                given(Opened(100))
                whenCommand(Deposit(-1)) { expectError<IllegalStateException>() }
            }
        }
        assertThrownBy<AssertionError> {
            aggregateSpec(model) {
                given(Opened(100))
                whenCommand(Deposit(1)) { expectError<IllegalArgumentException>() }
            }
        }
        assertThrownBy<AssertionError> {
            aggregateSpec(model) {
                given(Opened(100))
                whenCommand(Deposit(-1)) { outcome }
            }
        }
        assertThrownBy<AssertionError> {
            aggregateSpec(model) {
                given(Opened(1))
                whenCommand(Deposit(2)) { expectNoError() }
            }
        }
    }

    @Test
    fun `scenarios fork from a shared prefix`() = aggregateSpec(model) {
        whenCommand(Open(100)) { expectEventTypes(Opened::class) }
        ref("opened")
        whenCommand(Deposit(40)) { expectEvents(Deposited(40)) }
        fork("withdrawn") {
            whenCommand(Withdraw(40)) { expectState { balance.assert().isZero() } }
            fork("then closed") {
                whenCommand(Close("empty")) { expectEventTypes(Closed::class) }
            }
            whenCommand(Close("empty")) { expectEventTypes(Closed::class) }
        }
        fork("too much") {
            whenCommand(Withdraw(41)) { expectRejected("account.insufficient") }
        }
        fork(from = "opened") {
            whenCommand(Withdraw(1)) { expectRejected("account.insufficient") }
            advanceTime(30.minutes) { expectEvents(Closed("dormant")) }
        }
        // The forks changed nothing here: the deposit stays, and cancelled the dormancy schedule.
        whenCommand(Withdraw(40)) { expectState { balance.assert().isZero() } }
        expectNothingDue(within = 60.minutes)
    }

    @Test
    fun `a failure in a fork names it`() {
        assertThrownBy<ForkAssertionError> {
            aggregateSpec(model) {
                whenCommand(Open(100)) { expectEventTypes(Opened::class) }
                fork("outer") {
                    fork("inner") { whenCommand(Withdraw(1)) { expectEventTypes(Withdrawn::class) } }
                }
            }
        }.hasMessageStartingWith("In fork \"outer > inner\": Expected committed events")
    }

    @Test
    fun `a fork from an unknown ref fails`() {
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(model) { fork(from = "nowhere") { } }
        }
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(model) {
                ref("a")
                ref("a")
            }
        }
    }

    @Test
    fun `a fork keeps the time and the ids of its prefix`() = aggregateSpec(model) {
        whenCommand(Open(100)) { }
        advanceTime(10.minutes)
        fork {
            now.assert().isEqualTo(this@aggregateSpec.now)
            whenCommand(Deposit(1)) { expectEvents(Deposited(1)) }
            expectNothingDue(within = 30.minutes)
        }
        advanceTime(20.minutes) { expectEvents(Closed("dormant")) }
    }

    @Test
    fun `nothing due is checked without sending anything`() {
        aggregateSpec(model) {
            whenCommand(Open(100)) { }
            advanceTime(10.minutes)
            assertThrownBy<AssertionError> { expectNothingDue(within = 20.minutes) }.hasMessageContaining("dormant")
            advanceTime(5.minutes)
        }
        assertThrownBy<AssertionError> {
            aggregateSpec(model) {
                whenCommand(Open(100)) { }
                advanceTime(1.minutes) { }
            }
        }
    }

    data class Claim(val handle: String, val system: Boolean)
    data class Claimed(val handle: String, val system: Boolean)

    @Test
    fun `holds tells which keys the aggregate holds, by tenancy`() {
        val handles = aggregate<Account>("bank", "handle") {
            initial { Account(it.id) }
            decideSuspending<Claim>(Creation.ALLOWED) { _, body ->
                val tenancy = if (body.system) ReservationTenancy.SYSTEM else ReservationTenancy.TENANT
                if (!reserve("handle", body.handle, tenancy)) {
                    Decision.reject("handle.taken", body.handle)
                } else {
                    release("handle", "old", tenancy)
                    Decision.events(Claimed(body.handle, body.system))
                }
            }
            evolve<Claimed> { }
        }
        aggregateSpec(handles) {
            holds("handle", "ahoo").assert().isFalse()
            whenCommand(Claim("old", system = false)) { expectNoError() }
            holds("handle", "old").assert().isFalse()
            whenCommand(Claim("ahoo", system = false)) { expectNoError() }
            holds("handle", "ahoo").assert().isTrue()
            holds("handle", "ahoo", ReservationTenancy.SYSTEM).assert().isFalse()
            whenCommand(Claim("root", system = true)) { expectNoError() }
            holds("handle", "root", ReservationTenancy.SYSTEM).assert().isTrue()
            holds("handle", "root").assert().isFalse()
        }
    }

    private val claims = aggregate<Account>("bank", "handle") {
        initial { Account(it.id) }
        decideSuspending<Claim>(Creation.ALLOWED) { _, body ->
            val tenancy = if (body.system) ReservationTenancy.SYSTEM else ReservationTenancy.TENANT
            if (reserve("handle", body.handle, tenancy)) {
                Decision.events(Claimed(body.handle, body.system))
            } else {
                Decision.reject("handle.taken", body.handle)
            }
        }
        evolve<Claimed> { }
    }

    @Test
    fun `givenReservation gives a key to another aggregate, so reserving it is refused`() {
        aggregateSpec(claims) {
            givenReservation("handle", "ahoo")
            givenReservation("handle", "root", tenancy = ReservationTenancy.SYSTEM)
            whenCommand(Claim("ahoo", system = false)) { expectRejected("handle.taken") }
            whenCommand(Claim("root", system = true)) { expectRejected("handle.taken") }
            // Held per tenancy: the tenant's key leaves the system one free, and the other way round.
            whenCommand(Claim("ahoo", system = true)) { expectNoError() }
            holds("handle", "ahoo").assert().isFalse()
            fork {
                whenCommand(Claim("root", system = false)) { expectNoError() }
            }
            assertThrownBy<IllegalArgumentException> { givenReservation("handle", "x", owner = ref) }
            assertThrownBy<IllegalArgumentException> {
                givenReservation("handle", "x", owner = AggregateType("other", "handle").ref("tenant", "h"))
            }
        }
    }

    @Test
    fun `a rejection the scenario does not check fails it`() {
        aggregateSpec(claims) {
            givenReservation("handle", "ahoo")
            assertThrownBy<AssertionError> { whenCommand(Claim("ahoo", system = false)) { } }
                .hasMessageContaining("which the scenario does not check: expectRejected checks a rejection")
            assertThrownBy<AssertionError> {
                whenCommand(Claim("ahoo", system = false)) { expectState { id.assert().isEqualTo("aggregate-1") } }
            }
            // A scenario that reads the outcome checks it itself.
            whenCommand(Claim("ahoo", system = false)) {
                outcome.assert().isInstanceOf(CommandOutcome.Rejected::class.java)
            }
        }
    }
}
