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

package me.ahoo.wow.spi.reservation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.MutableClock
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * The [ReservationStore] contract (documentation/designs/v10/01-programming-model.md §7) every backend passes. A
 * backend's test extends it and supplies a store whose expiry follows [clock] from [createStore].
 */
abstract class ReservationStoreSpec {
    /** A store judging expiry by [clock]. Stores may share storage: each test uses namespaces of its own. */
    protected abstract fun createStore(clock: Clock): ReservationStore

    protected val clock = MutableClock()

    private val namespace = "ns-" + UUID.randomUUID()
    private val other = "$namespace-other"

    private val store: ReservationStore by lazy { createStore(clock) }

    @Test
    fun `a reservation is pending until its time to live passes`(): Unit = runBlocking {
        val reserved = store.reserve(namespace, "alice", "user-1", TTL)
        reserved.assert().isEqualTo(Reservation(namespace, "alice", "user-1", clock.millis() + TTL.inWholeMilliseconds))
        reserved!!.confirmed.assert().isFalse()
        store.find(namespace, "alice").assert().isEqualTo(reserved)
        store.find(namespace, "bob").assert().isNull()
        clock.advance(TTL)
        store.find(namespace, "alice").assert().isNull()
    }

    @Test
    fun `a live reservation keeps the key from another owner`(): Unit = runBlocking {
        val reserved = store.reserve(namespace, "alice", "user-1", TTL)
        store.reserve(namespace, "alice", "user-2", TTL).assert().isNull()
        clock.advance(TTL - 1.seconds)
        store.reserve(namespace, "alice", "user-2", TTL).assert().isNull()
        store.find(namespace, "alice").assert().isEqualTo(reserved)
    }

    @Test
    fun `concurrent reservations of one key let exactly one owner win`(): Unit = runBlocking {
        val outcomes = coroutineScope {
            (1..CONTENDERS).map { owner ->
                async(Dispatchers.Default) { store.reserve(namespace, "alice", "user-$owner", TTL) }
            }.awaitAll()
        }
        val winners = outcomes.filterNotNull()
        winners.size.assert().isEqualTo(1)
        store.find(namespace, "alice")?.owner.assert().isEqualTo(winners.single().owner)
    }

    @Test
    fun `an expired pending reservation is taken over by another owner`(): Unit = runBlocking {
        store.reserve(namespace, "alice", "user-1", TTL)
        clock.advance(TTL)
        val taken = store.reserve(namespace, "alice", "user-2", TTL)
        taken?.owner.assert().isEqualTo("user-2")
        store.find(namespace, "alice").assert().isEqualTo(taken)
        store.confirm(namespace, "alice", "user-1").assert().isFalse()
        store.release(namespace, "alice", "user-1").assert().isFalse()
        store.find(namespace, "alice").assert().isEqualTo(taken)
    }

    @Test
    fun `a confirmed reservation never expires`(): Unit = runBlocking {
        store.reserve(namespace, "alice", "user-1", TTL)
        store.confirm(namespace, "alice", "user-1").assert().isTrue()
        clock.advance(LONG_AFTER)
        store.reserve(namespace, "alice", "user-2", TTL).assert().isNull()
        store.find(namespace, "alice").assert().isEqualTo(Reservation(namespace, "alice", "user-1", null))
        store.confirm(namespace, "alice", "user-1").assert().isTrue()
    }

    @Test
    fun `an owner may still confirm its expired reservation while no one took the key`(): Unit = runBlocking {
        store.reserve(namespace, "alice", "user-1", TTL)
        clock.advance(TTL)
        store.find(namespace, "alice").assert().isNull()
        store.confirm(namespace, "alice", "user-1").assert().isTrue()
        store.find(namespace, "alice")?.confirmed.assert().isEqualTo(true)
    }

    @Test
    fun `only the owner confirms or releases a reservation`(): Unit = runBlocking {
        store.reserve(namespace, "alice", "user-1", TTL)
        store.confirm(namespace, "alice", "user-2").assert().isFalse()
        store.release(namespace, "alice", "user-2").assert().isFalse()
        store.find(namespace, "alice")?.owner.assert().isEqualTo("user-1")
        store.find(namespace, "alice")?.confirmed.assert().isEqualTo(false)
        store.confirm(namespace, "missing", "user-1").assert().isFalse()
        store.release(namespace, "missing", "user-1").assert().isFalse()
    }

    @Test
    fun `releasing frees the key, pending or confirmed`(): Unit = runBlocking {
        store.reserve(namespace, "alice", "user-1", TTL)
        store.release(namespace, "alice", "user-1").assert().isTrue()
        store.release(namespace, "alice", "user-1").assert().isFalse()
        store.find(namespace, "alice").assert().isNull()
        store.reserve(namespace, "alice", "user-2", TTL)?.owner.assert().isEqualTo("user-2")
        store.confirm(namespace, "alice", "user-2").assert().isTrue()
        store.release(namespace, "alice", "user-2").assert().isTrue()
        store.find(namespace, "alice").assert().isNull()
        store.reserve(namespace, "alice", "user-3", TTL)?.owner.assert().isEqualTo("user-3")
    }

    @Test
    fun `reserving again renews the owner's pending reservation and keeps a confirmed one`(): Unit = runBlocking {
        store.reserve(namespace, "alice", "user-1", TTL)
        clock.advance(TTL - 1.seconds)
        val renewed = store.reserve(namespace, "alice", "user-1", TTL)
        renewed?.expiresAt.assert().isEqualTo(clock.millis() + TTL.inWholeMilliseconds)
        clock.advance(TTL - 1.seconds)
        store.find(namespace, "alice").assert().isEqualTo(renewed)
        store.confirm(namespace, "alice", "user-1").assert().isTrue()
        store.reserve(namespace, "alice", "user-1", TTL).assert()
            .isEqualTo(Reservation(namespace, "alice", "user-1", null))
        store.find(namespace, "alice")?.confirmed.assert().isEqualTo(true)
    }

    @Test
    fun `namespaces are separate`(): Unit = runBlocking {
        store.reserve(namespace, "alice", "user-1", TTL)
        store.reserve(other, "alice", "user-2", TTL)?.owner.assert().isEqualTo("user-2")
        store.release(namespace, "alice", "user-1").assert().isTrue()
        store.find(other, "alice")?.owner.assert().isEqualTo("user-2")
    }

    protected companion object {
        val TTL = 30.seconds
        val LONG_AFTER = 3650.days
        const val CONTENDERS = 16
    }
}
