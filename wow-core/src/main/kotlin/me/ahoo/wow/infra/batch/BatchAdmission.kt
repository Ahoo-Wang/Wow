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

package me.ahoo.wow.infra.batch

import java.util.concurrent.Semaphore

/**
 * Maintains the global bounds and explicit ownership shared by every batch lane.
 */
internal class BatchAdmission<T : Any>(
    capacity: Int,
) {
    private val availableItems = Semaphore(capacity)
    private val availableQueueSlots = Semaphore(capacity)
    private val ownershipMonitor = Any()
    private val reservations = mutableSetOf<Reservation>()
    private val pending = mutableSetOf<BatchRequest<T>>()
    private val queued = mutableSetOf<BatchRequest<T>>()

    /**
     * A tracked lease held while user item construction is in progress.
     *
     * Failure and shutdown may release the lease before the factory returns.
     * A later [track] or [release] then becomes an idempotent no-op.
     */
    inner class Reservation internal constructor() {
        fun track(value: T): BatchRequest<T>? =
            this@BatchAdmission.track(this, value)

        fun release() {
            this@BatchAdmission.release(this)
        }
    }

    /**
     * Atomically reserves one live item and one physical queue slot.
     */
    fun tryReserve(): Reservation? =
        synchronized(ownershipMonitor) {
            if (!availableItems.tryAcquire()) {
                return@synchronized null
            }
            if (!availableQueueSlots.tryAcquire()) {
                availableItems.release()
                return@synchronized null
            }
            Reservation().also(reservations::add)
        }

    /**
     * Transfers a reservation to a queue-owned request. Returns `null` when
     * shutdown already reclaimed the reservation.
     */
    private fun track(
        reservation: Reservation,
        value: T,
    ): BatchRequest<T>? =
        synchronized(ownershipMonitor) {
            if (!reservations.remove(reservation)) {
                return@synchronized null
            }
            BatchRequest(
                value = value,
                onReleaseAdmission = ::releaseAdmission,
                onReleaseQueueSlot = ::releaseQueueSlot,
            ).also { request ->
                pending += request
                queued += request
            }
        }

    private fun release(reservation: Reservation) {
        synchronized(ownershipMonitor) {
            if (reservations.remove(reservation)) {
                availableQueueSlots.release()
                availableItems.release()
            }
        }
    }

    /**
     * Reclaims every factory-stage reservation without waiting for user code.
     */
    fun releaseReservations() {
        synchronized(ownershipMonitor) {
            if (reservations.isEmpty()) {
                return
            }
            val reservationCount = reservations.size
            reservations.clear()
            availableQueueSlots.release(reservationCount)
            availableItems.release(reservationCount)
        }
    }

    fun pendingSnapshot(): List<BatchRequest<T>> =
        synchronized(ownershipMonitor) {
            pending.toList()
        }

    fun ownedSnapshot(): List<BatchRequest<T>> =
        synchronized(ownershipMonitor) {
            buildSet {
                addAll(pending)
                addAll(queued)
            }.toList()
        }

    fun discardCancelledQueued() {
        val queuedSnapshot = synchronized(ownershipMonitor) {
            queued.toList()
        }
        queuedSnapshot.forEach(BatchRequest<T>::discardIfCancelled)
    }

    val pendingCount: Int
        get() = synchronized(ownershipMonitor) {
            pending.size
        }

    val queuedCount: Int
        get() = synchronized(ownershipMonitor) {
            queued.size
        }

    val reservationCount: Int
        get() = synchronized(ownershipMonitor) {
            reservations.size
        }

    private fun releaseAdmission(request: BatchRequest<T>) {
        synchronized(ownershipMonitor) {
            if (pending.remove(request)) {
                availableItems.release()
            }
        }
    }

    private fun releaseQueueSlot(request: BatchRequest<T>) {
        synchronized(ownershipMonitor) {
            if (queued.remove(request)) {
                availableQueueSlots.release()
            }
        }
    }
}
