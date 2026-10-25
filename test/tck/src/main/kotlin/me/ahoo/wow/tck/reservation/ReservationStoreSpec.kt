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

package me.ahoo.wow.tck.reservation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.Reservation
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.tck.MutableClock
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * The [ReservationStore] contract (documentation/designs/01-programming-model.md §7; findings A1-2, A1-3, A1-10)
 * every backend passes. A backend's test extends it and supplies a store whose expiry follows [clock] from
 * [createStore].
 */
@Suppress("TooManyFunctions") // One test per rule of the contract.
abstract class ReservationStoreSpec {
    /** A store judging expiry by [clock]. Stores may share storage: each test uses namespaces of its own. */
    protected abstract fun createStore(clock: Clock): ReservationStore

    protected val clock = MutableClock()

    private val namespace = "ns-" + UUID.randomUUID()
    private val scope = ReservationScope(CONTEXT, TENANT, namespace)
    private val user1 = owner("user-1")
    private val user2 = owner("user-2")

    private val store: ReservationStore by lazy { createStore(clock) }

    private fun pending(owner: AggregateRef, vararg holds: String, at: Long = clock.millis()) =
        Reservation(scope, "alice", owner, at + TTL.inWholeMilliseconds, holds.toSet())

    @Test
    fun `a reservation is pending until its time to live passes`(): Unit = runBlocking {
        val reserved = store.reserve(scope, "alice", user1, H1, TTL)
        reserved.assert().isEqualTo(pending(user1, H1))
        reserved!!.confirmed.assert().isFalse()
        store.find(scope, "alice").assert().isEqualTo(reserved)
        store.find(scope, "bob").assert().isNull()
        clock.advance(TTL)
        store.find(scope, "alice").assert().isNull()
    }

    @Test
    fun `a live reservation keeps the key from another owner`(): Unit = runBlocking {
        val reserved = store.reserve(scope, "alice", user1, H1, TTL)
        store.reserve(scope, "alice", user2, H2, TTL).assert().isNull()
        clock.advance(TTL - 1.seconds)
        store.reserve(scope, "alice", user2, H2, TTL).assert().isNull()
        store.find(scope, "alice").assert().isEqualTo(reserved)
    }

    @Test
    fun `concurrent reservations of one key let exactly one owner win`(): Unit = runBlocking {
        val outcomes = coroutineScope {
            (1..CONTENDERS).map { owner ->
                async(Dispatchers.Default) { store.reserve(scope, "alice", owner("user-$owner"), H1, TTL) }
            }.awaitAll()
        }
        val winners = outcomes.filterNotNull()
        winners.size.assert().isEqualTo(1)
        store.find(scope, "alice")?.owner.assert().isEqualTo(winners.single().owner)
    }

    @Test
    fun `an expired pending reservation is taken over by another owner`(): Unit = runBlocking {
        store.reserve(scope, "alice", user1, H1, TTL)
        clock.advance(TTL)
        val taken = store.reserve(scope, "alice", user2, H2, TTL)
        taken.assert().isEqualTo(pending(user2, H2))
        store.find(scope, "alice").assert().isEqualTo(taken)
        store.confirm(scope, "alice", user1, 1).assert().isEqualTo(ConfirmOutcome.Taken(taken!!))
        store.abandon(scope, "alice", user1, H1).assert().isFalse()
        store.release(scope, "alice", user1, 2).assert().isFalse()
        store.find(scope, "alice").assert().isEqualTo(taken)
    }

    @Test
    fun `a confirmed reservation never expires`(): Unit = runBlocking {
        store.reserve(scope, "alice", user1, H1, TTL)
        store.confirm(scope, "alice", user1, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        clock.advance(LONG_AFTER)
        store.reserve(scope, "alice", user2, H2, TTL).assert().isNull()
        store.find(scope, "alice").assert().isEqualTo(Reservation(scope, "alice", user1, null, version = 1))
        store.confirm(scope, "alice", user1, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.find(scope, "alice").assert().isEqualTo(Reservation(scope, "alice", user1, null, version = 1))
    }

    @Test
    fun `an owner may still confirm its expired reservation while no one took the key`(): Unit = runBlocking {
        store.reserve(scope, "alice", user1, H1, TTL)
        clock.advance(TTL)
        store.find(scope, "alice").assert().isNull()
        store.confirm(scope, "alice", user1, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.find(scope, "alice").assert().isEqualTo(Reservation(scope, "alice", user1, null, version = 1))
    }

    @Test
    fun `confirming a free key reinstates it for the committed fact`(): Unit = runBlocking {
        store.confirm(scope, "never-reserved", user1, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.find(scope, "never-reserved")?.owner.assert().isEqualTo(user1)

        store.reserve(scope, "alice", user1, H1, TTL)
        store.abandon(scope, "alice", user1, H1).assert().isTrue()
        store.find(scope, "alice").assert().isNull()
        store.confirm(scope, "alice", user1, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.find(scope, "alice").assert().isEqualTo(Reservation(scope, "alice", user1, null, version = 1))
    }

    @Test
    fun `confirming takes the key over from another owner's expired pending reservation only`(): Unit = runBlocking {
        val held = store.reserve(scope, "alice", user2, H2, TTL)!!
        store.confirm(scope, "alice", user1, 1).assert().isEqualTo(ConfirmOutcome.Taken(held))
        store.find(scope, "alice").assert().isEqualTo(held)
        clock.advance(TTL)
        store.confirm(scope, "alice", user1, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.find(scope, "alice").assert().isEqualTo(Reservation(scope, "alice", user1, null, version = 1))
        store.confirm(scope, "alice", user2, 1)
            .assert().isEqualTo(ConfirmOutcome.Taken(Reservation(scope, "alice", user1, null, version = 1)))
    }

    @Test
    fun `only the owner abandons, confirms or releases a reservation`(): Unit = runBlocking {
        val held = store.reserve(scope, "alice", user1, H1, TTL)!!
        store.abandon(scope, "alice", user2, H1).assert().isFalse()
        store.confirm(scope, "alice", user2, 1).assert().isEqualTo(ConfirmOutcome.Taken(held))
        store.release(scope, "alice", user2, 2).assert().isFalse()
        store.find(scope, "alice").assert().isEqualTo(held)
        store.abandon(scope, "missing", user1, H1).assert().isFalse()
        store.release(scope, "missing", user1, 2).assert().isFalse()
    }

    @Test
    fun `abandoning removes only the attempt's own hold`(): Unit = runBlocking {
        store.reserve(scope, "alice", user1, H1, TTL)
        store.reserve(scope, "alice", user1, H2, TTL).assert().isEqualTo(pending(user1, H1, H2))
        store.abandon(scope, "alice", user1, H2).assert().isTrue()
        store.abandon(scope, "alice", user1, H2).assert().isFalse()
        store.find(scope, "alice").assert().isEqualTo(pending(user1, H1))
        store.reserve(scope, "alice", user2, H3, TTL).assert().isNull()
        store.abandon(scope, "alice", user1, H1).assert().isTrue()
        store.find(scope, "alice").assert().isNull()
        store.reserve(scope, "alice", user2, H3, TTL).assert().isEqualTo(pending(user2, H3))
    }

    @Test
    fun `abandoning never frees a confirmed key`(): Unit = runBlocking {
        store.reserve(scope, "alice", user1, H1, TTL)
        store.reserve(scope, "alice", user1, H2, TTL)
        store.confirm(scope, "alice", user1, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.abandon(scope, "alice", user1, H2).assert().isFalse()
        store.abandon(scope, "alice", user1, H1).assert().isFalse()
        store.find(scope, "alice").assert().isEqualTo(Reservation(scope, "alice", user1, null, version = 1))
        store.reserve(
            scope,
            "alice",
            user1,
            H3,
            TTL
        ).assert().isEqualTo(Reservation(scope, "alice", user1, null, version = 1))
        store.abandon(scope, "alice", user1, H3).assert().isFalse()
        store.find(scope, "alice").assert().isEqualTo(Reservation(scope, "alice", user1, null, version = 1))
    }

    @Test
    fun `an unknown hold abandons nothing`(): Unit = runBlocking {
        store.reserve(scope, "alice", user1, H1, TTL)
        store.abandon(scope, "alice", user1, H3).assert().isFalse()
        store.find(scope, "alice").assert().isEqualTo(pending(user1, H1))
    }

    @Test
    fun `releasing frees the key, pending or confirmed`(): Unit = runBlocking {
        store.reserve(scope, "alice", user1, H1, TTL)
        store.reserve(scope, "alice", user1, H2, TTL)
        store.release(scope, "alice", user1, 2).assert().isTrue()
        store.release(scope, "alice", user1, 2).assert().isFalse()
        store.find(scope, "alice").assert().isNull()
        store.reserve(scope, "alice", user2, H1, TTL)?.owner.assert().isEqualTo(user2)
        store.confirm(scope, "alice", user2, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.release(scope, "alice", user2, 2).assert().isTrue()
        store.find(scope, "alice").assert().isNull()
        store.reserve(scope, "alice", owner("user-3"), H1, TTL)?.owner.assert().isEqualTo(owner("user-3"))
    }

    @Test
    fun `reserving again adds the hold, keeps the later expiry and keeps a confirmed reservation`(): Unit =
        runBlocking {
            store.reserve(scope, "alice", user1, H1, TTL)
            clock.advance(TTL - 1.seconds)
            val renewed = store.reserve(scope, "alice", user1, H2, TTL)
            renewed.assert().isEqualTo(pending(user1, H1, H2))
            store.reserve(scope, "alice", user1, H1, 2.seconds).assert().isEqualTo(renewed)
            clock.advance(TTL - 1.seconds)
            store.find(scope, "alice").assert().isEqualTo(renewed)
            store.confirm(scope, "alice", user1, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
            store.reserve(
                scope,
                "alice",
                user1,
                H3,
                TTL
            ).assert().isEqualTo(Reservation(scope, "alice", user1, null, version = 1))
            store.find(scope, "alice")?.confirmed.assert().isEqualTo(true)
        }

    @Test
    fun `reserving an own expired pending reservation again starts it afresh`(): Unit = runBlocking {
        store.reserve(scope, "alice", user1, H1, TTL)
        store.reserve(scope, "alice", user1, H2, TTL)
        clock.advance(TTL)
        store.reserve(scope, "alice", user1, H3, TTL).assert().isEqualTo(pending(user1, H3))
        store.abandon(scope, "alice", user1, H1).assert().isFalse()
        store.abandon(scope, "alice", user1, H3).assert().isTrue()
        store.find(scope, "alice").assert().isNull()
    }

    @Test
    fun `concurrent reservations and abandons of one owner keep the holds still in flight`(): Unit = runBlocking {
        store.reserve(scope, "alice", user1, "kept", TTL)
        coroutineScope {
            (1..CONTENDERS).map { attempt ->
                async(Dispatchers.Default) {
                    store.reserve(scope, "alice", user1, "h$attempt", TTL).assert().isNotNull()
                    if (attempt % 2 == 0) {
                        store.abandon(scope, "alice", user1, "h$attempt").assert().isTrue()
                    }
                }
            }.awaitAll()
        }
        val expected = (1..CONTENDERS).filter { it % 2 == 1 }.map { "h$it" }.toSet() + "kept"
        store.find(scope, "alice")?.holds.assert().isEqualTo(expected)
    }

    @Test
    fun `namespaces are separate`(): Unit = runBlocking {
        val other = scope.copyWith(namespace = "$namespace-other")
        store.reserve(scope, "alice", user1, H1, TTL)
        store.reserve(other, "alice", user2, H1, TTL)?.owner.assert().isEqualTo(user2)
        store.release(scope, "alice", user1, 2).assert().isTrue()
        store.find(other, "alice")?.owner.assert().isEqualTo(user2)
    }

    @Test
    fun `tenants are separate`(): Unit = runBlocking {
        val otherTenant = scope.copyWith(tenantId = "tenant-b")
        val elsewhere = owner("user-1", tenant = "tenant-b")
        val mine = store.reserve(scope, "alice", user1, H1, TTL)
        val theirs = store.reserve(otherTenant, "alice", elsewhere, H1, TTL)
        theirs.assert().isEqualTo(Reservation(otherTenant, "alice", elsewhere, mine!!.expiresAt, setOf(H1)))
        store.confirm(otherTenant, "alice", elsewhere, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.find(scope, "alice").assert().isEqualTo(mine)
        store.release(otherTenant, "alice", elsewhere, 2).assert().isTrue()
        store.find(scope, "alice").assert().isEqualTo(mine)
        store.find(otherTenant, "alice").assert().isNull()
    }

    @Test
    fun `contexts are separate`(): Unit = runBlocking {
        val otherContext = scope.copyWith(context = "$CONTEXT-other")
        val elsewhere = AggregateType("$CONTEXT-other", "user").ref(TENANT, "user-1")
        val mine = store.reserve(scope, "alice", user1, H1, TTL)
        store.reserve(otherContext, "alice", elsewhere, H1, TTL)?.owner.assert().isEqualTo(elsewhere)
        store.release(otherContext, "alice", elsewhere, 2).assert().isTrue()
        store.find(scope, "alice").assert().isEqualTo(mine)
    }

    @Test
    fun `an owner is its fields, not a joined name`(): Unit = runBlocking {
        // `t` / `x/y` and `t/x` / `y` join to the same `context.user/t/x/y` (finding A1-10).
        val slashed = AggregateType(CONTEXT, "user").ref("t", "x/y")
        val tenantSlashed = AggregateType(CONTEXT, "user").ref("t/x", "y")
        val dotted = AggregateType(CONTEXT, "user.v2").ref("t.\$x", "#id ✓")
        val reserved = store.reserve(scope, "alice", slashed, H1, TTL)
        reserved?.owner.assert().isEqualTo(slashed)
        store.reserve(scope, "alice", tenantSlashed, H2, TTL).assert().isNull()
        store.confirm(scope, "alice", tenantSlashed, 1).assert().isEqualTo(ConfirmOutcome.Taken(reserved!!))
        store.release(scope, "alice", tenantSlashed, 2).assert().isFalse()
        store.abandon(scope, "alice", tenantSlashed, H1).assert().isFalse()
        store.find(scope, "alice").assert().isEqualTo(reserved)
        store.reserve(scope, "bob", dotted, "\$hold.x", TTL)?.owner.assert().isEqualTo(dotted)
        store.confirm(scope, "bob", dotted, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.find(scope, "bob").assert().isEqualTo(Reservation(scope, "bob", dotted, null, version = 1))
    }

    @Test
    fun `a system key is unique across tenants and apart from the tenants' own keys`(): Unit = runBlocking {
        val system = scope.copyWith(tenantId = ReservationScope.SYSTEM_TENANT)
        val elsewhere = owner("user-2", tenant = "tenant-b")
        store.reserve(system, "alice", user1, H1, TTL)?.scope?.isSystem.assert().isEqualTo(true)
        store.reserve(system, "alice", elsewhere, H2, TTL).assert().isNull()
        store.confirm(system, "alice", user1, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.reserve(system, "alice", elsewhere, H2, TTL).assert().isNull()
        store.find(system, "alice").assert().isEqualTo(Reservation(system, "alice", user1, null, version = 1))
        store.reserve(scope, "alice", user2, H1, TTL)?.owner.assert().isEqualTo(user2)
    }

    @Test
    fun `a release frees only a key no later fact confirmed`(): Unit = runBlocking {
        store.reserve(scope, "alice", user1, H1, TTL)
        store.confirm(scope, "alice", user1, 3).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.release(scope, "alice", user1, 2).assert().isFalse()
        store.find(scope, "alice").assert().isEqualTo(Reservation(scope, "alice", user1, null, version = 3))
        // A fact that reserves and releases a key gives it up.
        store.release(scope, "alice", user1, 3).assert().isTrue()
        store.find(scope, "alice").assert().isNull()
    }

    @Test
    fun `confirming keeps the version of the latest fact`(): Unit = runBlocking {
        store.confirm(scope, "alice", user1, 3)
        store.confirm(scope, "alice", user1, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.find(scope, "alice")?.version.assert().isEqualTo(3)
        store.confirm(scope, "alice", user1, 5)
        store.find(scope, "alice")?.version.assert().isEqualTo(5)
        store.release(scope, "alice", user1, 4).assert().isFalse()
    }

    @Test
    fun `a pending key is released by any fact, and any version frees a confirmed one`(): Unit = runBlocking {
        store.reserve(scope, "alice", user1, H1, TTL)?.version.assert().isEqualTo(ReservationStore.UNVERSIONED)
        store.release(scope, "alice", user1, 1).assert().isTrue()
        store.confirm(scope, "bob", user1, 7)
        store.reserve(scope, "bob", user1, H1, TTL)?.version.assert().isEqualTo(7)
        store.release(scope, "bob", user1, ReservationStore.ANY_VERSION).assert().isTrue()
        store.find(scope, "bob").assert().isNull()
    }

    @Test
    fun `a reservation is refused a time to live that is not positive and an owner of another context`() {
        assertThrownBy<IllegalArgumentException> {
            runBlocking { store.reserve(scope, "alice", user1, H1, Duration.ZERO) }
        }
        assertThrownBy<IllegalArgumentException> {
            runBlocking { store.reserve(scope, "alice", AggregateType("elsewhere", "user").ref(TENANT, "u"), H1, TTL) }
        }
    }

    private fun owner(id: String, tenant: String = TENANT): AggregateRef = AggregateType(
        CONTEXT,
        "user"
    ).ref(tenant, id)

    protected companion object {
        val TTL = 30.seconds
        val LONG_AFTER = 3650.days
        const val CONTENDERS = 16
        const val CONTEXT = "tck"
        const val TENANT = "tenant-a"
        const val H1 = "hold-1"
        const val H2 = "hold-2"
        const val H3 = "hold-3"
    }
}
