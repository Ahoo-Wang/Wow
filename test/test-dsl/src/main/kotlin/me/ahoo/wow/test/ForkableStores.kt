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

import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.Reservation
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration

/** A write a store took, kept so that a fork rebuilds an equal store. */
internal typealias TimerWrite = suspend (TimerStore) -> Unit

/**
 * The timers of a specification in memory, which keep the writes they took: a fork rebuilds an equal store from
 * [writes], without running the commands that made them again.
 */
internal class ForkableTimerStore(writes: List<TimerWrite> = emptyList()) : TimerStore {
    private val store = InMemoryTimerStore()
    private val journal = ArrayList(writes)

    init {
        runBlocking { writes.forEach { it(store) } }
    }

    /** The writes the store took, in order. */
    val writes: List<TimerWrite> get() = journal.toList()

    private suspend fun <T> write(op: suspend (TimerStore) -> T): T = op(store).also { journal += { op(it) } }

    override suspend fun put(timer: Timer): Boolean = write { it.put(timer) }

    override suspend fun cancel(owner: AggregateRef, key: String, version: Long): Boolean =
        write { it.cancel(owner, key, version) }

    override suspend fun cancelAll(owner: AggregateRef, version: Long): Int = write { it.cancelAll(owner, version) }

    override suspend fun settle(changes: List<TimerChange>): Int = write { it.settle(changes) }

    override suspend fun find(owner: AggregateRef, key: String): Timer? = store.find(owner, key)

    override suspend fun due(partitions: Set<Int>, until: Long, limit: Int): List<Timer> =
        store.due(partitions, until, limit)

    override suspend fun parked(target: AggregateRef): List<Timer> = store.parked(target)
}

/** A write a reservation store took at a time ([at], the store's clock then). */
internal class ReservationWrite(val at: Long, val op: suspend (ReservationStore) -> Unit)

/**
 * The reservations of a specification in memory, which keep the writes they took, each with the time it was taken
 * at: a fork rebuilds an equal store from [writes], the holds expiring when they did. Expiry follows
 * the system clock, as [InMemoryReservationStore]'s does by default.
 */
internal class ForkableReservationStore(writes: List<ReservationWrite> = emptyList()) : ReservationStore {
    /** The time a write is replayed at; null to read the system clock. */
    private var replaying: Long? = null
    private val store = InMemoryReservationStore(ReplayClock())
    private val journal = ArrayList(writes)

    init {
        runBlocking {
            writes.forEach { write ->
                replaying = write.at
                write.op(store)
            }
            replaying = null
        }
    }

    /** The writes the store took, in order. */
    val writes: List<ReservationWrite> get() = journal.toList()

    private fun now(): Long = replaying ?: System.currentTimeMillis()

    private suspend fun <T> write(op: suspend (ReservationStore) -> T): T {
        val at = now()
        return op(store).also { journal += ReservationWrite(at) { op(it) } }
    }

    override suspend fun reserve(
        scope: ReservationScope,
        key: String,
        owner: AggregateRef,
        hold: String,
        ttl: Duration,
    ): Reservation? = write { it.reserve(scope, key, owner, hold, ttl) }

    override suspend fun abandon(scope: ReservationScope, key: String, owner: AggregateRef, hold: String): Boolean =
        write { it.abandon(scope, key, owner, hold) }

    override suspend fun confirm(
        scope: ReservationScope,
        key: String,
        owner: AggregateRef,
        version: Int,
    ): ConfirmOutcome = write { it.confirm(scope, key, owner, version) }

    override suspend fun release(scope: ReservationScope, key: String, owner: AggregateRef, version: Int): Boolean =
        write { it.release(scope, key, owner, version) }

    override suspend fun find(scope: ReservationScope, key: String): Reservation? = store.find(scope, key)

    /** The store's clock: the time a write is replayed at, the system's otherwise. */
    private inner class ReplayClock : Clock() {
        override fun millis(): Long = now()

        override fun instant(): Instant = Instant.ofEpochMilli(millis())

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }
}
