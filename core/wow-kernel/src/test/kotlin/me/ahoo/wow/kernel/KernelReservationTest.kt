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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import org.junit.jupiter.api.Test

/** Decision D38: a decide function reserves a unique key, and the kernel settles it with the append. */
class KernelReservationTest {
    data class Register(val name: String, val refuse: Boolean = false)
    data class Registered(val name: String)

    class User(val id: String) {
        var name: String? = null
    }

    private val model = aggregate<User>("test", "user") {
        initial { User(it.id) }
        decideSuspending<Register>(Creation.ALLOWED) { _, body ->
            when {
                !reserve(NAMES, body.name) -> Decision.reject("name.taken", body.name)
                body.refuse -> Decision.reject("refused", body.name)
                else -> Decision.events(Registered(body.name))
            }
        }
        evolve<Registered> { name = it.name }
    }
    private val reservations = InMemoryReservationStore()
    private val kernel = Kernel(InMemoryEventLog(), reservations = reservations)
    private var sequence = 0

    private suspend fun register(user: String, name: String, refuse: Boolean = false): CommandOutcome =
        kernel.execute(
            model,
            Command("c-${++sequence}", "r-$sequence", model.type.ref("t", user), Register(name, refuse))
        )

    @Test
    fun `a committed command confirms its reservation`() = runTest {
        register("u-1", "ahoo").assert().isInstanceOf(CommandOutcome.Committed::class.java)
        val reservation = reservations.find(NAMES, "ahoo")!!
        reservation.confirmed.assert().isTrue()
        reservation.owner.assert().isEqualTo("test.user/t/u-1")
    }

    @Test
    fun `a key another aggregate holds is refused`() = runTest {
        register("u-1", "ahoo")
        (register("u-2", "ahoo") as CommandOutcome.Rejected).rejection.code.assert().isEqualTo("name.taken")
    }

    @Test
    fun `a rejected command releases its reservation`() = runTest {
        register("u-1", "ahoo", refuse = true)
        reservations.find(NAMES, "ahoo").assert().isNull()
        register("u-2", "ahoo").assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    @Test
    fun `a key already held confirmed stays held when a later command is rejected`() = runTest {
        register("u-1", "ahoo")
        register("u-1", "ahoo", refuse = true)
        reservations.find(NAMES, "ahoo")!!.confirmed.assert().isTrue()
    }

    private companion object {
        const val NAMES = "user.name"
    }
}
