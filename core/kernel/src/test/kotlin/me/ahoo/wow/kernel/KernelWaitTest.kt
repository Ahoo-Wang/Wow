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

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test

/** Decision D25: the kernel writes a command's wait context into its entry only when the wait goes beyond COMMITTED. */
class KernelWaitTest {
    data class Register(val name: String)
    data class Registered(val name: String)

    class User(val id: String)

    private val model = aggregate<User>("test", "user") {
        initial { User(it.id) }
        decideSuspending<Register>(Creation.ALLOWED) { _, body ->
            if (body.name.startsWith("unique-")) reserve("names", body.name)
            Decision.events(Registered(body.name))
        }
        evolve<Registered> { }
    }
    private val log = InMemoryEventLog(partitions = 1)
    private val kernel = Kernel(log, reservations = InMemoryReservationStore())
    private val origin = Origin("node-1", "10.0.0.1:9090", "users")
    private var sequence = 0

    private fun wait(vararg hops: Hop) = WaitContext("w-$sequence", origin, hops.toList(), 1_700_000_000_000L)

    private suspend fun register(name: String, wait: WaitContext?): CommandOutcome = kernel.execute(
        model,
        Command("c-${++sequence}", "r-$sequence", model.type.ref("t", "u-$sequence"), Register(name)),
        wait,
    )

    @Test
    fun `the wait context goes into the entry without its hops up to COMMITTED`(): Unit = runTest {
        val beyond = wait(Hop.accepted("test"), Hop.committed("test"), Hop.snapshot("test"))
        register("a", beyond)
        val entry = log.tail(0).take(1).toList().single()
        entry.decodedTransport.assert().isEqualTo(
            Transport(wait = beyond.copyWith(remaining = listOf(Hop.snapshot("test"))))
        )
    }

    @Test
    fun `a wait that ends at COMMITTED writes nothing, nor does a command without a wait`(): Unit = runTest {
        register("a", wait(Hop.committed("test")))
        register("b", null)
        log.tail(0).take(2).toList().map { it.transport }.assert().containsExactly(null, null)
    }

    @Test
    fun `the wait context travels beside the keys an attempt reserved`(): Unit = runTest {
        val beyond = wait(Hop.handled("user.saga", "test"), Hop.committed("test"))
        register("unique-ahoo", beyond)
        log.tail(0).take(1).toList().single().decodedTransport.assert().isEqualTo(
            Transport(wait = beyond, reservations = listOf(ReservationKey("names", "unique-ahoo"))),
        )
    }
}
