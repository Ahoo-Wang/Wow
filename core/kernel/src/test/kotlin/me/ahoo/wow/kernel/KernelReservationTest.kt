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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.ReservationTenancy
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.reservation.Reservation
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.reservation.ReservationScope
import org.junit.jupiter.api.Test

/** Decisions D38, D50: a decide function reserves and releases unique keys, and the kernel settles them with the append. */
class KernelReservationTest {
    data class Register(val name: String, val refuse: Boolean = false, val system: Boolean = false)
    data class Registered(val name: String)
    data class Rename(val name: String, val refuse: Boolean = false)
    data class Renamed(val name: String)
    data class ReleaseOnly(val name: String)

    class User(val id: String) {
        var name: String? = null
    }

    /** Gates a decision of the command with that id after it reserved: the test opens it. */
    private val gates = HashMap<String, Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>>()

    private val model = aggregate<User>("test", "user") {
        initial { User(it.id) }
        decideSuspending<Register>(Creation.ALLOWED) { _, body ->
            val tenancy = if (body.system) ReservationTenancy.SYSTEM else ReservationTenancy.TENANT
            when {
                !reserve(NAMES, body.name, tenancy) -> Decision.reject("name.taken", body.name)
                body.refuse -> Decision.reject("refused", body.name)
                else -> Decision.events(Registered(body.name))
            }
        }
        decideSuspending<Rename> { state, body ->
            if (!reserve(NAMES, body.name)) {
                return@decideSuspending Decision.reject("name.taken", body.name)
            }
            gates[command.id]?.let { (reserved, open) ->
                reserved.complete(Unit)
                open.await()
            }
            when {
                state.name == body.name -> Decision.NoOp
                body.refuse -> Decision.reject("refused", body.name)
                else -> {
                    state.name?.let { release(NAMES, it) }
                    Decision.events(Renamed(body.name))
                }
            }
        }
        decideSuspending<ReleaseOnly>(Creation.ALLOWED) { _, body ->
            release(NAMES, body.name)
            release(NAMES, body.name)
            Decision.NoOp
        }
        evolve<Registered> { name = it.name }
        evolve<Renamed> { name = it.name }
    }
    private val reservations = InMemoryReservationStore()
    private val log = InMemoryEventLog(partitions = 1)
    private val kernel = Kernel(log, reservations = reservations)
    private var sequence = 0

    private suspend fun execute(user: String, body: Any, tenant: String = "t", on: Kernel = kernel): CommandOutcome {
        sequence++
        return on.execute(model, Command("c-$sequence", "r-$sequence", model.type.ref(tenant, user), body))
    }

    private suspend fun register(user: String, name: String, refuse: Boolean = false, tenant: String = "t") =
        execute(user, Register(name, refuse), tenant)

    private suspend fun find(name: String, tenant: String = "t"): Reservation? =
        reservations.find(ReservationScope("test", tenant, NAMES), name)

    @Test
    fun `a committed command confirms its reservation`() = runTest {
        register("u-1", "ahoo").assert().isInstanceOf(CommandOutcome.Committed::class.java)
        find("ahoo").assert().isEqualTo(
            Reservation(ReservationScope("test", "t", NAMES), "ahoo", model.type.ref("t", "u-1"), null),
        )
    }

    @Test
    fun `a key another aggregate holds is refused`() = runTest {
        register("u-1", "ahoo")
        (register("u-2", "ahoo") as CommandOutcome.Rejected).rejection.code.assert().isEqualTo("name.taken")
    }

    @Test
    fun `a rejected command gives its reservation up`() = runTest {
        register("u-1", "ahoo", refuse = true)
        find("ahoo").assert().isNull()
        register("u-2", "ahoo").assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    @Test
    fun `a key already held confirmed stays held when a later command is rejected`() = runTest {
        register("u-1", "ahoo")
        register("u-1", "ahoo", refuse = true)
        find("ahoo")!!.confirmed.assert().isTrue()
    }

    @Test
    fun `the keys an attempt reserved pending travel with its entry`(): Unit = runTest {
        register("u-1", "ahoo")
        register("u-1", "ahoo")
        val entries = log.tail(0).take(2).toList()
        // The first attempt reserved the key pending; the second found it confirmed and had nothing to confirm.
        entries[0].transport.assert().isEqualTo(Transport(reservations = listOf(ReservationKey(NAMES, "ahoo"))))
        entries[1].transport.assert().isNull()
    }

    @Test
    fun `two tenants each hold the same key`() = runTest {
        // Finding A1-3: a key is unique within its tenant, so another tenant's registration neither fails nor probes.
        register("u-1", "ahoo", tenant = "tenant-a").assert().isInstanceOf(CommandOutcome.Committed::class.java)
        register("u-1", "ahoo", tenant = "tenant-b").assert().isInstanceOf(CommandOutcome.Committed::class.java)
        find("ahoo", "tenant-a")?.owner.assert().isEqualTo(model.type.ref("tenant-a", "u-1"))
        find("ahoo", "tenant-b")?.owner.assert().isEqualTo(model.type.ref("tenant-b", "u-1"))
    }

    @Test
    fun `a system key is unique across tenants`() = runTest {
        execute("u-1", Register("root", system = true), "tenant-a").assert()
            .isInstanceOf(CommandOutcome.Committed::class.java)
        (execute("u-2", Register("root", system = true), "tenant-b") as CommandOutcome.Rejected)
            .rejection.code.assert().isEqualTo("name.taken")
        register("u-2", "root", tenant = "tenant-b").assert().isInstanceOf(CommandOutcome.Committed::class.java)
        reservations.find(ReservationScope("test", ReservationScope.SYSTEM_TENANT, NAMES), "root")?.owner
            .assert().isEqualTo(model.type.ref("tenant-a", "u-1"))
        log.tail(0).take(1).toList().single().transport
            .assert().isEqualTo(Transport(reservations = listOf(ReservationKey(NAMES, "root", system = true))))
    }

    @Test
    fun `a conflicting attempt never frees the key another attempt of its aggregate confirmed`() = runTest {
        // Finding A1-2: two writers of one aggregate (a handover, a forward that fell back to local) share its log and
        // its reservations. B reserves the key and waits; A renames to the same key, commits and confirms; B then
        // conflicts and compensates, which must give up B's hold only.
        register("u-1", "first")
        val other = Kernel(log, reservations = reservations)
        val reserved = CompletableDeferred<Unit>()
        val open = CompletableDeferred<Unit>()
        gates["c-${sequence + 1}"] = reserved to open
        val slow = async { execute("u-1", Rename("ahoo"), on = other) }
        reserved.await()
        find("ahoo")?.confirmed.assert().isEqualTo(false)
        execute("u-1", Rename("ahoo")).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        find("ahoo")?.confirmed.assert().isEqualTo(true)
        open.complete(Unit)
        // Decided again on the committed rename: nothing left to do.
        slow.await().assert().isInstanceOf(CommandOutcome.Unchanged::class.java)
        find("ahoo").assert().isEqualTo(
            Reservation(ReservationScope("test", "t", NAMES), "ahoo", model.type.ref("t", "u-1"), null),
        )
        register("u-2", "ahoo").assert().isInstanceOf(CommandOutcome.Rejected::class.java)
    }

    @Test
    fun `a rename reserves the new key and releases the old one once committed`() = runTest {
        register("u-1", "old")
        execute("u-1", Rename("new")).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        find("old").assert().isNull()
        find("new")!!.confirmed.assert().isTrue()
        log.tail(0).take(2).toList()[1].transport.assert().isEqualTo(
            Transport(
                reservations = listOf(ReservationKey(NAMES, "new")),
                releases = listOf(ReservationKey(NAMES, "old"))
            ),
        )
        register("u-2", "old").assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    @Test
    fun `a release is not applied when the events are not appended`() = runTest {
        register("u-1", "old")
        execute("u-1", Rename("new", refuse = true)).assert().isInstanceOf(CommandOutcome.Rejected::class.java)
        find("old")!!.confirmed.assert().isTrue()
        find("new").assert().isNull()
        // A decision that appends no event releases nothing either.
        execute("u-1", ReleaseOnly("old")).assert().isInstanceOf(CommandOutcome.Unchanged::class.java)
        find("old")!!.confirmed.assert().isTrue()
    }

    @Test
    fun `reserving or releasing without a reservation store fails`() = runTest {
        val bare = Kernel(log)
        runCatching { execute("u-1", Register("ahoo"), on = bare) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalStateException::class.java)
        runCatching { execute("u-1", ReleaseOnly("ahoo"), on = bare) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `an unknown append the version slot shows committed confirms its key and compensates nothing`() = runTest {
        // Finding A1-1: the backend commits, then reports the outcome unknown (a timeout after the write).
        val unknownAfterCommit = object : EventLog by log {
            override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome {
                log.append(stream, transport)
                return AppendOutcome.Unknown(IllegalStateException("timed out"))
            }
        }
        val trail = mutableListOf<String>()
        val hooked = aggregate<User>("test", "user") {
            initial { User(it.id) }
            decideSuspending<Register>(Creation.ALLOWED) { _, body ->
                reserve(NAMES, body.name)
                onAppendFailure { trail += "compensated" }
                afterAppend { trail += "after" }
                Decision.events(Registered(body.name))
            }
            evolve<Registered> { name = it.name }
        }
        val cached = HashMap<AggregateRef, CachedState>()
        val states = object : StateCache {
            override fun take(ref: AggregateRef): CachedState? = cached.remove(ref)

            override fun put(ref: AggregateRef, state: CachedState) {
                cached[ref] = state
            }
        }
        val kernel = Kernel(unknownAfterCommit, reservations = reservations, states = states)
        val outcome = kernel.execute(hooked, Command("c-1", "r-1", model.type.ref("t", "u-1"), Register("ahoo")))

        outcome.assert().isInstanceOf(CommandOutcome.Committed::class.java)
        find("ahoo")!!.confirmed.assert().isTrue()
        trail.assert().containsExactly("after")
        states.take(model.type.ref("t", "u-1"))!!.version.assert().isEqualTo(1)
    }

    private companion object {
        const val NAMES = "user.name"
    }
}
