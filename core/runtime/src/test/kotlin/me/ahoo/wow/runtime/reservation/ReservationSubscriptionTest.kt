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
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.ReservationScope
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
    data class Move(val name: String)

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
        decideSuspending<Move> { state, body ->
            when {
                reserve(NAMES, body.name) -> {
                    state.name?.let { release(NAMES, it) }
                    Decision.events(Renamed(body.name))
                }

                else -> Decision.reject("taken", body.name)
            }
        }
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
        val confirms = CopyOnWriteArrayList<Pair<String, AggregateRef>>()
        val releases = CopyOnWriteArrayList<Pair<String, AggregateRef>>()

        /** The keys a release freed. */
        val freed = CopyOnWriteArrayList<String>()

        @Volatile
        var failing = false

        override suspend fun confirm(
            scope: ReservationScope,
            key: String,
            owner: AggregateRef,
            version: Int,
        ): ConfirmOutcome {
            confirms += key to owner
            check(!failing) { "The reservation store is down." }
            return delegate.confirm(scope, key, owner, version)
        }

        override suspend fun release(scope: ReservationScope, key: String, owner: AggregateRef, version: Int): Boolean {
            releases += key to owner
            return delegate.release(scope, key, owner, version).also { if (it) freed += key }
        }
    }

    /** The kernel's view of the store: its after-append actions never happen, as when the node crashed. */
    private class CrashBeforeConfirm(private val delegate: ReservationStore) : ReservationStore by delegate {
        override suspend fun confirm(
            scope: ReservationScope,
            key: String,
            owner: AggregateRef,
            version: Int,
        ): ConfirmOutcome = ConfirmOutcome.Confirmed

        override suspend fun release(scope: ReservationScope, key: String, owner: AggregateRef, version: Int) = true
    }

    private val clock = TestClock()
    private val reservations = InMemoryReservationStore(clock)
    private val store = RecordingStore(reservations)
    private val log = InMemoryEventLog(partitions = 2)
    private val kernel = Kernel(log, reservations = CrashBeforeConfirm(reservations), reservationTtl = TTL)
    private val lost = CopyOnWriteArrayList<String>()
    private val observer = object : Observer {
        override fun reservationLost(context: String) {
            lost += context
        }
    }
    private val confirming = ReservationSubscription("shop", store, observer)
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

    private suspend fun find(name: String) = reservations.find(SCOPE, name)

    private suspend fun confirmedBy(name: String, id: String): Boolean =
        find(name)?.let { it.confirmed && it.owner == user(id) } == true

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
        store.confirms.toList().assert().containsExactly("ahoo" to user("u-1"))
    }

    @Test
    fun `a crash between the append and the confirmation is recovered from the log`(): Unit = runBlocking {
        // The node commits and crashes before confirming: the key is left pending, and expires.
        register("u-1", "ahoo").assert().isInstanceOf(CommandOutcome.Committed::class.java)
        find("ahoo")!!.confirmed.assert().isFalse()
        clock.now += (TTL * 2).inWholeMilliseconds
        find("ahoo").assert().isNull()

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
        reservations.release(SCOPE, "ahoo", user("u-1"), ReservationStore.ANY_VERSION).assert().isTrue()
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
    fun `replaying an old rename never frees the key a later entry confirmed again`(): Unit = runBlocking {
        // The kernel confirmed and released after each append: u-1 holds ahoo again, confirmed by its third entry.
        val live = Kernel(log, reservations = reservations, reservationTtl = TTL)
        listOf(Register("ahoo"), Move("bob"), Move("ahoo")).forEachIndexed { n, body ->
            live.execute(model, Command("live-$n", "live-$n", user("u-1"), body))
                .assert().isInstanceOf(CommandOutcome.Committed::class.java)
        }
        confirmedBy("ahoo", "u-1").assert().isTrue()

        // A subscription far behind replays the entries: the second's release of ahoo is an older fact's.
        runtime().start(setOf(0, 1))
        eventually { store.releases.size == 2 }
        store.freed.toList().assert().isEqualTo(listOf("bob"))
        confirmedBy("ahoo", "u-1").assert().isTrue()
        find("bob").assert().isNull()
    }

    @Test
    fun `a key taken after its pending reservation expired stays with whoever took it`(): Unit = runBlocking {
        register("u-1", "ahoo")
        clock.now += (TTL * 2).inWholeMilliseconds
        reservations.reserve(SCOPE, "ahoo", user("someone-else"), "hold", TTL).assert().isNotNull()

        runtime().start(setOf(0, 1))
        eventually { lost.isNotEmpty() }
        find("ahoo")!!.owner.assert().isEqualTo(user("someone-else"))
        lost.toList().assert().isEqualTo(listOf("shop"))
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
            find("ahoo")!!.confirmed.assert().isFalse()

            store.failing = false
            runtime.resume(Hop.RESERVATION_SUBSCRIPTION, user("u-1")).assert().isTrue()
            confirmedBy("ahoo", "u-1").assert().isTrue()
            ledger.isParked(Hop.RESERVATION_SUBSCRIPTION, user("u-1")).assert().isFalse()
        }

    @Test
    fun `a release lost to a crash after the append is applied from the log`(): Unit = runBlocking {
        register("u-1", "old")
        // The node commits the move and crashes before releasing: the old key is still held.
        execute("u-1", Move("new")).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        find("old")?.owner.assert().isEqualTo(user("u-1"))

        runtime().start(setOf(0, 1))
        eventually { find("old") == null && confirmedBy("new", "u-1") }
        store.releases.toList().assert().containsExactly("old" to user("u-1"))
        register("u-2", "old").assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    @Test
    fun `a replayed release never frees a key another aggregate holds since`(): Unit = runBlocking {
        register("u-1", "old")
        execute("u-1", Move("new"))
        val first = runtime()
        first.start(setOf(0, 1))
        eventually { find("old") == null }
        first.stop()
        register("u-2", "old").assert().isInstanceOf(CommandOutcome.Committed::class.java)

        store.releases.clear()
        store.confirms.clear()
        runtime(InMemoryCheckpointStore()).start(setOf(0, 1))
        eventually { store.releases.isNotEmpty() && store.confirms.size == 3 }
        confirmedBy("old", "u-2").assert().isTrue()
        confirmedBy("new", "u-1").assert().isTrue()
    }

    private companion object {
        const val NAMES = "user.name"
        val SCOPE = ReservationScope("shop", "t", NAMES)
        val TTL = 1.seconds
    }
}
