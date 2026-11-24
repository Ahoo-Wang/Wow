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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.spi.reservation.Reservation
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Deep review r1 (kernel): [ThrownRejections] answers a decide function's plain [IllegalStateException] as the
 * refusal `wow.command.refused` (D311, D314), a fault of the caller's command that is never retried. The kernel's own
 * port that a decide function calls, [ReservationStore] through `DecideScope.reserve`, runs inside that same mapping:
 * a store that fails with a plain `IllegalStateException` is answered as the caller's refusal, with the store's message
 * as the rejection's detail. The MongoDB driver throws exactly that (`IllegalStateException("state should be: open")`)
 * for every call on a client that is closed, as on a node that is stopping, and `MongoFailures.translate` only wraps a
 * `MongoException`.
 */
class R1PortFailureAsRefusalTest {
    data class Register(val name: String)
    data class Registered(val name: String)

    class User(val id: String) {
        var name: String? = null
    }

    private val model = aggregate<User>("test", "user") {
        initial { User(it.id) }
        decideSuspending<Register>(Creation.ALLOWED) { _, body ->
            if (!reserve("user.name", body.name)) reject("user.name-taken", "${body.name} is taken.")
            Decision.events(Registered(body.name))
        }
        evolve<Registered> { name = it.name }
    }
    private val ref = model.type.ref("t", "u-1")

    /** A store whose client is closed, failing as the MongoDB driver fails every call then. */
    private val closed = object : ReservationStore by InMemoryReservationStore() {
        override suspend fun reserve(
            scope: ReservationScope,
            key: String,
            owner: AggregateRef,
            hold: String,
            ttl: Duration,
        ): Reservation? = throw IllegalStateException("state should be: open")
    }

    @Test
    fun `a reservation store that fails is a failure of the command, not the caller's refusal`() = runTest {
        val kernel = Kernel(InMemoryEventLog(), reservations = closed)
        var outcome: CommandOutcome? = null
        val failure = runCatching {
            outcome = kernel.execute(model, Command("c-1", "r-1", ref, Register("ahoo")))
        }.exceptionOrNull()
        outcome.assert().describedAs("the store's failure answered as the caller's refusal").isNull()
        failure.assert().isInstanceOf(IllegalStateException::class.java)
    }

    data class Remind(val at: Long)
    class Unknown

    /** Schedules a command class this aggregate does not decide: the code's mistake, as for another aggregate type. */
    private val scheduling = aggregate<User>("test", "user") {
        initial { User(it.id) }
        decide<Remind>(Creation.ALLOWED) { _, body ->
            schedule("remind", ScheduleRule.after(body.at.milliseconds), Unknown())
            Decision.events(Registered("x"))
        }
        evolve<Registered> { name = it.name }
    }

    @Test
    fun `scheduling a command the aggregate does not decide is a failure of the command, not the caller's refusal`() =
        runTest {
            // The same mistake for another aggregate type is a Misuse, a failure: AttemptScopes.scheduledCommand.
            val kernel = Kernel(InMemoryEventLog())
            var outcome: CommandOutcome? = null
            val failure = runCatching {
                outcome = kernel.execute(scheduling, Command("c-1", "r-1", ref, Remind(1_000)))
            }.exceptionOrNull()
            outcome.assert().describedAs("the model's mistake answered as the caller's invalid command").isNull()
            failure.assert().isNotNull()
        }
}
