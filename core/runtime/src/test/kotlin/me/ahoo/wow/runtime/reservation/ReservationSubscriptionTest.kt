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

package me.ahoo.wow.runtime.reservation

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.InMemoryCheckpointStore
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryFailureLedger
import me.ahoo.wow.kernel.InMemoryReservationStore
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** The built-in `reservation` subscription (decision D50): it confirms the keys a committed entry reserved pending. */
class ReservationSubscriptionTest {
    data class Register(val name: String)
    data class Registered(val name: String)
    data class Rename(val name: String)
    data class Renamed(val name: String)

    class User {
        var name: String? = null
    }

    private val model = aggregate<User>("shop", "user") {
        initial { User() }
        decideSuspending<Register>(Creation.ALLOWED) { _, body ->
            when {
                reserve(NAMES, body.name) -> Decision.events(Registered(body.name))
                else -> Decision.reject("taken", body.name)
            }
        }
        decide<Rename> { _, body -> Decision.events(Renamed(body.name)) }
        evolve<Registered> { name = it.name }
        evolve<Renamed> { name = it.name }
    }

    /** A clock that stands still until a test moves it, so a test decides when a pending reservation expires. */
    private class TestClock : Clock() {
        @Volatile
        var now = 1_700_000_000_000L

        override fun millis(): Long = now

        override fun instant(): Instant = Instant.ofEpochMilli(now)

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    /**
     * The store the subscription confirms through: it records each confirmation asked of it, and throws while
     * [failing] is set.
     */
    private class RecordingStore(private val delegate: ReservationStore) : ReservationStore by delegate {
        val confirms = CopyOnWriteArrayList<Pair<String, String>>()

        @Volatile
        var failing = false

        override suspend fun confirm(namespace: String, key: String, owner: String): Boolean {
            confirms += key to owner
            check(!failing) { "The reservation store is down." }
            return delegate.confirm(namespace, key, owner)
        }
    }

    /** The kernel's view of the store: its after-append confirmation never happens, as when the node crashed. */
    private class CrashBeforeConfirm(private val delegate: ReservationStore) : ReservationStore by delegate {
        override suspend fun confirm(namespace: String, key: String, owner: String): Boolean = true
    }

    private val clock = TestClock()
    private val reservations = InMemoryReservationStore(clock)
    private val store = RecordingStore(reservations)
    private val log = InMemoryEventLog(partitions = 2)
    private val kernel = Kernel(log, reservations = CrashBeforeConfirm(reservations), reservationTtl = TTL)
    private val confirming = ReservationSubscription("shop", store)
    private val ledger = InMemoryFailureLedger()
    private val sequence = AtomicInteger()
    private val runtimes = CopyOnWriteArrayList<SubscriptionRuntime>()

    private fun runtime(
        checkpoints: CheckpointStore = InMemoryCheckpointStore(),
        failures: FailureLedger = ledger,
    ) = SubscriptionRuntime(
        "shop",
        log,
        checkpoints,
        failures,
        listOf(confirming.subscription),
        testOptions(),
    ).also { runtimes += it }

    private fun user(id: String): AggregateRef = model.type.ref("t", id)

    private suspend fun execute(id: String, body: Any): CommandOutcome {
        val n = sequence.incrementAndGet()
        return kernel.execute(model, Command("c-$n", "r-$n", user(id), body))
    }

    private suspend fun register(id: String, name: String): CommandOutcome = execute(id, Register(name))

    private suspend fun confirmedBy(name: String, id: String): Boolean =
        reservations.find(NAMES, name)?.let { it.confirmed && it.owner == user(id).toString() } == true

    @AfterEach
    fun stop(): Unit = runBlocking {
        runtimes.forEach { it.stop() }
    }

    @Test
    fun `is the reservation subscription of the context`() {
        confirming.subscription.name.assert().isEqualTo("reservation")
        confirming.subscription.filter.context.assert().isEqualTo("shop")
        confirming.subscription.filter.aggregates.assert().isEmpty()
        confirming.subscription.kind.assert().isEqualTo(FunctionKind.RESERVATION)
    }

    @Test
    fun `a committed entry's pending keys are confirmed, so they outlive the pending expiry`(): Unit = runBlocking {
        runtime().start(setOf(0, 1))
        register("u-1", "ahoo").assert().isInstanceOf(CommandOutcome.Committed::class.java)
        execute("u-1", Rename("ahoo-wang")).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        eventually { confirmedBy("ahoo", "u-1") }

        clock.now += (TTL * 2).inWholeMilliseconds
        confirmedBy("ahoo", "u-1").assert().isTrue()
        register("u-2", "ahoo").assert().isInstanceOf(CommandOutcome.Rejected::class.java)
        // Only the entry that reserved asked the store; the rename and the rejection carried no keys.
        store.confirms.toList().assert().containsExactly("ahoo" to user("u-1").toString())
    }

    @Test
    fun `a crash between the append and the confirmation is recovered from the log`(): Unit = runBlocking {
        // The node commits and crashes before confirming: the key is left pending, and expires.
        register("u-1", "ahoo").assert().isInstanceOf(CommandOutcome.Committed::class.java)
        reservations.find(NAMES, "ahoo")!!.confirmed.assert().isFalse()
        clock.now += (TTL * 2).inWholeMilliseconds
        reservations.find(NAMES, "ahoo").assert().isNull()

        // The partition's next owner reads the entry from its checkpoint and confirms the key, expired or not.
        runtime().start(setOf(0, 1))
        eventually { confirmedBy("ahoo", "u-1") }
        register("u-2", "ahoo").assert().isInstanceOf(CommandOutcome.Rejected::class.java)
    }

    @Test
    fun `a replay confirms again harmlessly and never takes a key back`(): Unit = runBlocking {
        register("u-1", "ahoo")
        register("u-3", "kept")
        val first = runtime()
        first.start(setOf(0, 1))
        eventually { confirmedBy("ahoo", "u-1") && confirmedBy("kept", "u-3") }
        first.stop()
        // A saga moved the key: u-1 released it and u-2 holds it now.
        reservations.release(NAMES, "ahoo", user("u-1").toString()).assert().isTrue()
        register("u-2", "ahoo").assert().isInstanceOf(CommandOutcome.Committed::class.java)

        // A new checkpoint replays every entry from the beginning.
        store.confirms.clear()
        runtime(InMemoryCheckpointStore()).start(setOf(0, 1))
        eventually { store.confirms.size == 3 }
        confirmedBy("ahoo", "u-2").assert().isTrue()
        confirmedBy("kept", "u-3").assert().isTrue()
        ledger.isParked(Hop.RESERVATION_SUBSCRIPTION, user("u-1")).assert().isFalse()
    }

    @Test
    fun `a key taken after its pending reservation expired stays with whoever took it`(): Unit = runBlocking {
        register("u-1", "ahoo")
        clock.now += (TTL * 2).inWholeMilliseconds
        reservations.reserve(NAMES, "ahoo", "someone-else", TTL).assert().isNotNull()

        runtime().start(setOf(0, 1))
        eventually { store.confirms.isNotEmpty() }
        reservations.find(NAMES, "ahoo")!!.owner.assert().isEqualTo("someone-else")
        ledger.isParked(Hop.RESERVATION_SUBSCRIPTION, user("u-1")).assert().isFalse()
    }

    @Test
    fun `a failing store parks the aggregate, and resuming it confirms from the entries' transport`(): Unit =
        runBlocking {
            store.failing = true
            val runtime = runtime()
            runtime.start(setOf(0, 1))
            register("u-1", "ahoo")
            eventually { ledger.isParked(Hop.RESERVATION_SUBSCRIPTION, user("u-1")) }
            reservations.find(NAMES, "ahoo")!!.confirmed.assert().isFalse()

            store.failing = false
            runtime.resume(Hop.RESERVATION_SUBSCRIPTION, user("u-1")).assert().isTrue()
            confirmedBy("ahoo", "u-1").assert().isTrue()
            ledger.isParked(Hop.RESERVATION_SUBSCRIPTION, user("u-1")).assert().isFalse()
        }

    private companion object {
        const val NAMES = "user.name"
        val TTL = 1.seconds
    }
}
