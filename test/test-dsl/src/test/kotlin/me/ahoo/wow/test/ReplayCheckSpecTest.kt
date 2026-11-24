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
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * A state the specification rebuilds from its log once the clock has moved on is the state each command left: a read
 * of the clock or of a random source, in a property or a sourcing function, fails every run, not now and then.
 */
class ReplayCheckSpecTest {
    data class Open(val holder: String)
    data class Touch(val note: String)
    data class Opened(val holder: String)
    data class Touched(val note: String)

    class Ticket {
        var holder = ""
        var openedAt = 0L
        var note = ""
        var token = ""
    }

    /** A ticket whose [Touched] is sourced by [touched], [Opened] stamped with its commit's time. */
    private fun model(touched: Ticket.(Touched) -> Unit): AggregateModel<Ticket> = aggregate("desk", "ticket") {
        initial { Ticket() }
        decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.holder)) }
        decide<Touch> { _, body -> Decision.events(Touched(body.note)) }
        evolve<Opened> { event, commit ->
            holder = event.holder
            openedAt = commit.commitTime
        }
        evolve<Touched> { touched(it) }
    }

    private val pure = model { note = it.note }

    @RepeatedTest(5)
    fun `a sourcing function that reads the clock should fail every run, naming the property and the way out`() {
        val stamped = model { note = "${it.note} at ${System.currentTimeMillis()}" }
        assertThrownBy<AssertionError> {
            aggregateSpec(stamped) {
                given(Opened("alice"))
                whenCommand(Touch("seen")) { expectEventTypes(Touched::class) }
            }
        }.hasMessageStartingWith(
            "Touch (command-2): Rebuilt from its log once the clock had moved on, the state is not the one the " +
                "command left (expected the command's, was the rebuilt one's):\n  note: expected \"seen at ",
        ).hasMessageEndingWith(
            "A state is a function of its events: take the time from the event, its commit or the command's " +
                "@OccurredAt, not the clock; a random value is made in the command function and carried by the event.",
        )
    }

    @RepeatedTest(5)
    fun `a sourcing function that reads a random source should fail every run`() {
        val random = model { token = UUID.randomUUID().toString() }
        assertThrownBy<AssertionError> {
            aggregateSpec(random) {
                given(Opened("alice"))
                whenCommand(Touch("seen")) { expectEventTypes(Touched::class) }
            }
        }.hasMessageContaining("  token: expected \"")
    }

    /** A ticket whose age it writes, read from the clock once it is opened: before, the purity check sees a 0. */
    class AgingTicket {
        var openedAt = 0L
        val age: Long get() = if (openedAt == 0L) 0 else System.currentTimeMillis() - openedAt
    }

    @RepeatedTest(5)
    fun `a property that reads the clock should fail every run`() {
        val aging = aggregate<AgingTicket>("desk", "aging") {
            initial { AgingTicket() }
            decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.holder)) }
            evolve<Opened> { openedAt = it.holder.length.toLong() }
        }
        assertThrownBy<AssertionError> {
            aggregateSpec(aging) { whenCommand(Open("alice")) { expectEventTypes(Opened::class) } }
        }.hasMessageContaining("  age: expected ")
    }

    @Test
    fun `a state its events determine should pass, its time read from the commit`() = aggregateSpec(pure) {
        whenCommand(Open("alice")) { expectState { openedAt.assert().isEqualTo(DEFAULT_START.toEpochMilli()) } }
        advanceTime(5.minutes)
        whenCommand(Touch("seen")) { expectState { note.assert().isEqualTo("seen") } }
        fork("again") { whenCommand(Touch("again")) { expectState { note.assert().isEqualTo("again") } } }
    }

    @Test
    fun `a wait whose thread is descheduled past its deadline should go on when the clock moved meanwhile`() {
        // #6776: the clock is read not yet past, then the thread sleeps past the deadline, and the clock has moved.
        val wall = ArrayDeque(listOf(1_000L, 1_001L))
        var nanos = 0L
        ReplayCheck.awaitTick(1_000, wallClock = { wall.removeFirst() }, monotonic = { nanos.also { nanos += PAST } })
        wall.assert().isEmpty()
    }

    @Test
    fun `a clock that does not move on past the deadline should fail the wait, whatever the thread`() {
        var nanos = 0L
        assertThrownBy<IllegalStateException> {
            ReplayCheck.awaitTick(1_000, wallClock = { 1_000L }, monotonic = { nanos.also { nanos += PAST } })
        }.hasMessageStartingWith("The JVM's clock did not move on in 100ms")
    }

    @Test
    fun `a clock that does not move on should fail the wait, saying why`() {
        assertThrownBy<IllegalStateException> { ReplayCheck.awaitTick(Long.MAX_VALUE) }.hasMessage(
            "The JVM's clock did not move on in 100ms, so a state rebuilt from the log cannot be told apart from one " +
                "that read the clock.",
        )
    }

    @Test
    fun `a given state should be rebuilt from itself and the streams since, and pass`() = aggregateSpec(pure) {
        givenState(version = 3) {
            holder = "alice"
            openedAt = 42
            token = "given"
        }
        whenCommand(Touch("seen")) {
            expectVersion(4)
            expectState {
                token.assert().isEqualTo("given")
                note.assert().isEqualTo("seen")
            }
        }
        givenCommands(Touch("twice"))
    }
}

/** More than the replay check's wait on the monotonic clock: a deschedule past its deadline. */
private const val PAST = 200_000_000L
